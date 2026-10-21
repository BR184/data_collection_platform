package com.data.collection.platform.service.read;

import com.data.collection.platform.bi.infrastructure.BiCatReadPublicationService;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 应用启动后按完整来源证据后台接管；关闭平台调度时不查询、不安装、不登记请求。 */
@Component
public class ReadModelInstallationScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReadModelInstallationScheduler.class);
  private final JdbcTemplate jdbc;
  private final ReadModelInitialInstallation facts;
  private final BiCatReadPublicationService cat;
  private final TransactionTemplate transactions;
  private final ReadProducerRetryService retries;

  /** 复用已有完整认证入口和事务管理器；创建调度器本身不访问数据库。 */
  public ReadModelInstallationScheduler(JdbcTemplate jdbc, ReadModelInitialInstallation facts,
      BiCatReadPublicationService cat, TransactionTemplate transactions, ReadProducerRetryService retries) {
    this.jdbc = jdbc;
    this.facts = facts;
    this.cat = cat;
    this.transactions = transactions;
    this.retries = retries;
  }

  /** 每轮至多接管一个尚无完整指针的事实域；来源拒绝/失败持久退避，让后续域继续。 */
  @Scheduled(scheduler = "readPublicationScheduler", fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void installOneFactSource() {
    var sources = jdbc.query("""
        select distinct s.source_instance, s.fact_type from source_fact_publication_states s
          left join read_publication_domains d on d.producer_kind = 'GITLAB_FACT'
           and d.source_instance = s.source_instance and d.fact_type = s.fact_type and d.table_key = ''
         where d.current_publication_id is null
           and coalesce(d.producer_retry_after, '-infinity'::timestamptz) <= current_timestamp
         order by 1, 2 limit 1
        """, (rs, row) -> ReadPublicationDomainKey.gitlabFact(rs.getString(1), FactType.valueOf(rs.getString(2))));
    if (sources.isEmpty()) { return; }
    var key = sources.getFirst();
    try {
      if (!facts.installGitlabSource(key.sourceInstance(), FactType.valueOf(key.factType()))) {
        throw new IllegalStateException("来源暂不具备初始完整接管资格");
      }
      retries.succeeded(key);
    } catch (RuntimeException failure) {
      retries.failed(key, failure);
      LOGGER.warn("初始事实接管未完成，domain={}，已退避", key, failure);
    }
  }

  /** 每轮至多接管一个已发布 CAT 快照，沿用原事务认证，不复制快照内容。 */
  @Scheduled(scheduler = "readPublicationScheduler", fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void installOneCatSnapshot() {
    var snapshots = jdbc.query("""
        select p.snapshot_id, p.published_version, 'cat:' || p.product_version_id || ':' || p.test_stage as table_key
          from bi_cat_test_publications p
          left join read_publication_domains d on d.producer_kind = 'CAT_MIRROR'
           and d.source_instance = 'default' and d.fact_type = 'DATASET'
           and d.table_key = 'cat:' || p.product_version_id || ':' || p.test_stage
          left join read_publication_cat_snapshots ref on ref.publication_id = d.current_publication_id
           and ref.snapshot_id = p.snapshot_id
         where ref.publication_id is null
           and coalesce(d.producer_retry_after, '-infinity'::timestamptz) <= current_timestamp
         order by p.product_version_id, p.test_stage limit 1
        """, (rs, row) -> new CatSnapshot(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)));
    if (snapshots.isEmpty()) { return; }
    CatSnapshot snapshot = snapshots.getFirst();
    var key = new ReadPublicationDomainKey(ReadPublicationProducerKind.CAT_MIRROR, "default", "DATASET", snapshot.key());
    try {
      transactions.executeWithoutResult(status -> cat.publishSnapshot(snapshot.id(), snapshot.version()));
      retries.succeeded(key);
    } catch (RuntimeException failure) {
      retries.failed(key, failure);
      LOGGER.warn("初始 CAT 快照接管未完成，domain={}，已退避", key, failure);
    }
  }

  private record CatSnapshot(UUID id, long version, String key) {}
}
