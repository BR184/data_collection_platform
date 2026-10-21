package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 成熟生产数据的一次性接管入口；只有正证据和稳定输入栅栏同时成立才激活初始读版本。 */
@Service
public class ReadModelInitialInstallation {
  private final JdbcTemplate jdbc;
  private final ReadPublicationRepository repository;
  private final ReadPublicationService lifecycle;
  private final SyncFactPublicationStateService production;
  private final SyncFactPublicationVerification verification;

  public ReadModelInitialInstallation(JdbcTemplate jdbc, ReadPublicationRepository repository, ReadPublicationService lifecycle,
      SyncFactPublicationStateService production, SyncFactPublicationVerification verification) {
    this.jdbc = jdbc;
    this.repository = repository;
    this.lifecycle = lifecycle;
    this.production = production;
    this.verification = verification;
  }

  /**
   * 在安装阶段固定证据、事实及父子关系；忙或不合格返回 false，当前域已有完整指针时幂等返回 true。
   * 不接管未知/重复/半回填事实，不为无证据来源制造空版本；不能作为日常读请求的回退入口。
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public boolean installGitlabSource(String sourceInstance, FactType factType) {
    var key = ReadPublicationDomainKey.gitlabFact(sourceInstance, factType);
    var existing = repository.findDomain(key).orElse(null);
    if (existing != null && existing.currentPublicationId() != null) { return true; }
    PublicationFactTable table = PublicationFactTable.of(factType);
    if (!repository.tryLockInitialVersionInputs(table) || !verification.verify(key).admissible()) { return false; }
    Instant cutoff = jdbc.queryForObject("""
        select min(coalesce(run.finished_at, run.started_at, run.created_at))::timestamptz
          from source_fact_dependency_states dep join sync_runs run on run.id = dep.latest_mirror_run_id
         where dep.source_instance = ? and dep.fact_type = ? and dep.readiness_status = 'READY'
        """, java.time.OffsetDateTime.class, key.sourceInstance(), factType.name()).toInstant();
    long covered = jdbc.queryForObject("select coalesce(max(published_version), 0) from fact_change_heads"
        + " where source_instance = ? and fact_type = ?", Long.class, key.sourceInstance(), factType.name());
    return lifecycle.bootstrapInitialVersion(key, "GITLAB", cutoff,
        production.sourceSeal(key.sourceInstance(), factType, covered), covered, verification) != null;
  }
}
