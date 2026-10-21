package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.service.DatasetProducerCatalog;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 合并事务已提交的数据集变更；每轮至多一个拥有者，失败保留完整指针和可诊断的持久请求。 */
@Service
public class DatasetRefreshWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(DatasetRefreshWorker.class);
  private final JdbcTemplate jdbc;
  private final DatasetProducerCatalog catalog;
  private final DatasetPublicationService datasets;

  public DatasetRefreshWorker(JdbcTemplate jdbc, DatasetProducerCatalog catalog, DatasetPublicationService datasets) {
    this.jdbc = jdbc;
    this.catalog = catalog;
    this.datasets = datasets;
  }

  private void ensureInitialRequests() {
    jdbc.update("insert into read_dataset_refresh_requests(producer_kind) values ('FORMAL_RECORD') on conflict do nothing");
    // 只接管当前启用的来源；模式切换/装载事务仍负责登记后续变更，不能让未配置来源抢占正式态。
    jdbc.update("""
        insert into read_dataset_refresh_requests(producer_kind)
        select owner from code_review_match_mode_db_settings settings
          cross join (values ('COMPAT_WALKTHROUGH'), ('COMPAT_REVIEW')) owners(owner)
         where settings.id = 1 and settings.enabled
           and ((owner = 'COMPAT_WALKTHROUGH' and settings.code_review_read_mode = 'compatibility')
             or (owner = 'COMPAT_REVIEW' and settings.review_data_read_mode = 'compatibility'))
        on conflict do nothing
        """);
  }

  /** 调度由平台 background-jobs 控制；本地数据发布不依赖 GitLab 镜像调度是否开启。 */
  @Scheduled(scheduler = "readPublicationScheduler", fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void publishOneRequestedDataset() {
    ensureInitialRequests();
    UUID lease = UUID.randomUUID();
    var claims = jdbc.query("""
        with next_request as (
          select producer_kind, source_instance, table_key from read_dataset_refresh_requests
           where producer_kind in ('FORMAL_RECORD', 'COMPAT_WALKTHROUGH', 'COMPAT_REVIEW')
             and requested_revision > completed_revision and retry_after <= current_timestamp
             and (lease_until is null or lease_until < current_timestamp)
           order by case when producer_kind = 'FORMAL_RECORD' then 1 else 0 end,
                    updated_at, producer_kind for update skip locked limit 1
        ) update read_dataset_refresh_requests request set lease_owner = ?, lease_until = current_timestamp + interval '10 minutes'
          from next_request next where request.producer_kind = next.producer_kind
           and request.source_instance = next.source_instance and request.table_key = next.table_key
        returning request.producer_kind, request.source_instance, request.table_key, request.requested_revision
        """, (rs, row) -> new Claim(ReadPublicationProducerKind.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getLong(4)), lease);
    if (claims.isEmpty()) { return; }
    Claim claim = claims.getFirst();
    String runId = "dataset:" + claim.owner() + ":" + claim.revision();
    try {
      // 崩溃发生在捕获提交与请求结算之间时，恢复同一持久候选，而不是抢占另一生产者。
      var prepared = jdbc.queryForList("select id from read_publications where producer_run_id = ?"
          + " and state in ('PROJECTING', 'READY_TO_PUBLISH')", UUID.class, runId);
      var publication = prepared.isEmpty() ? datasets.prepareCompleteDataset(catalog.request(claim.owner(), runId)) : null;
      UUID publicationId = publication == null ? prepared.getFirst() : publication.id();
      if (publication == null || publication.state() != com.data.collection.platform.entity.read.ReadPublicationState.PUBLISHED) {
        datasets.publishRequestedDataset(publicationId, runId, claim.owner().name(), lease);
      }
      jdbc.update("""
          update read_dataset_refresh_requests set completed_revision = greatest(completed_revision, ?),
            lease_owner = null, lease_until = null, last_error = null, retry_after = current_timestamp
           where producer_kind = ? and source_instance = ? and table_key = ? and lease_owner = ?
          """, claim.revision(), claim.owner().name(), claim.source(), claim.table(), lease);
    } catch (RuntimeException failure) {
      LOGGER.warn("本地完整数据集生产失败，owner={}, run={}", claim.owner(), runId, failure);
      Boolean stillOwned = jdbc.queryForObject("select exists(select 1 from read_dataset_refresh_requests"
          + " where producer_kind = ? and source_instance = ? and table_key = ? and lease_owner = ?"
          + " and lease_until > current_timestamp)", Boolean.class, claim.owner().name(), claim.source(), claim.table(), lease);
      if (!Boolean.TRUE.equals(stillOwned)) { return; }
      // 本次的冻结输入被拒绝时释放自己的候选，其他拥有者/更新请求继续保留。
      try {
        datasets.abortRequestedDataset(runId, claim.owner().name(), lease, failure.getMessage());
      } catch (RuntimeException cleanupFailure) {
        if (cleanupFailure != failure) { failure.addSuppressed(cleanupFailure); }
        LOGGER.warn("本地数据集失败清理未完成，保留首错并退避，run={}", runId, failure);
      }
      jdbc.update("""
          update read_dataset_refresh_requests set lease_owner = null, lease_until = null,
            last_error = ?, retry_after = current_timestamp + interval '1 minute'
           where producer_kind = ? and source_instance = ? and table_key = ? and lease_owner = ?
          """, failure.getMessage(), claim.owner().name(), claim.source(), claim.table(), lease);
    }
  }

  private record Claim(ReadPublicationProducerKind owner, String source, String table, long revision) {}
}
