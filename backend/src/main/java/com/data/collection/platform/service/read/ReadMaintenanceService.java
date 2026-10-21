package com.data.collection.platform.service.read;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 失联页面和无引用历史的有界维护；页面引用和请求共享锁仍由统一回收入口核验。 */
@Service
public class ReadMaintenanceService {
  private final JdbcTemplate jdbc;
  private final ReadPublicationService publications;
  private final ReadFallbackProperties properties;

  public ReadMaintenanceService(JdbcTemplate jdbc, ReadPublicationService publications, ReadFallbackProperties properties) {
    this.jdbc = jdbc;
    this.publications = publications;
    this.properties = properties;
  }

  /** 删除本轮确实已过期的页面租约；锁后重查截止，正在读取/续约的页面让位。 */
  @Transactional
  public int expireViews() {
    return jdbc.update("""
        with expired as (
          select id from read_view_contexts where expires_at < current_timestamp
           order by expires_at, id for update skip locked limit ?
        ) delete from read_view_contexts view using expired
           where view.id = expired.id and view.expires_at < current_timestamp
        """, properties.getGcBatchSize());
  }

  /** 每次只维护一个有历史的域，版本排他保护和整个域的行预算仍在统一生命周期内执行。 */
  @Transactional
  public void collectOneDomain() {
    var domains = jdbc.query("""
        select d.id, d.producer_kind, d.source_instance, d.fact_type, d.table_key from read_publication_domains d
         where exists(select 1 from read_publications p where p.domain_id = d.id
             and p.id <> d.current_publication_id and p.state in ('PUBLISHED', 'RETIRING', 'RETIRED')
             and p.published_at < current_timestamp - (? * interval '1 minute'))
         order by d.last_gc_at nulls first, d.id for update of d skip locked limit 1
        """, (rs, row) -> new MaintenanceDomain(rs.getObject(1, java.util.UUID.class),
            new ReadPublicationDomainKey(ReadPublicationProducerKind.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5))),
        properties.getRetirementGraceMinutes());
    if (domains.isEmpty()) { return; }
    MaintenanceDomain selected = domains.getFirst();
    publications.collectGarbage(selected.key());
    jdbc.update("update read_publication_domains set last_gc_at = current_timestamp where id = ?", selected.id());
  }

  private record MaintenanceDomain(java.util.UUID id, ReadPublicationDomainKey key) {}

  /** 每轮至多清理一个已中止事实候选的一批；域→发布锁序不变，忙碌域让位，失败保留持久标识。 */
  @Transactional
  public void collectAbortedFactCandidate() {
    var domains = jdbc.queryForList("""
        select d.id from read_publication_domains d
         where d.fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')
           and exists(select 1 from read_publications p where p.domain_id = d.id
             and p.state = 'ABORTED' and p.abort_cleanup_pending)
         order by d.last_gc_at nulls first, d.id for update of d skip locked limit 1
        """, java.util.UUID.class);
    if (domains.isEmpty()) { return; }
    var candidates = jdbc.queryForList("""
        select id from read_publications where domain_id = ? and state = 'ABORTED'
          and abort_cleanup_pending order by updated_at, id for update skip locked limit 1
        """, java.util.UUID.class, domains.getFirst());
    if (candidates.isEmpty()) { return; }
    publications.cleanupAbortedFactCandidate(candidates.getFirst());
    jdbc.update("update read_publication_domains set last_gc_at = current_timestamp where id = ?",
        domains.getFirst());
  }
}
