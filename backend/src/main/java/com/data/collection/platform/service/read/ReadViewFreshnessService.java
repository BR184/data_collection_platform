package com.data.collection.platform.service.read;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.DatasetReadSelection;
import com.data.collection.platform.entity.read.FactReadQueryContext;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.PublishedDatasetReadContext;
import com.data.collection.platform.entity.read.PublishedReadContext;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.entity.read.ReadSelection;
import com.data.collection.platform.entity.read.ReadView;
import com.data.collection.platform.entity.read.ReadViewMetadata;
import com.data.collection.platform.service.PlatformPermissionService;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 实时披露和管理员告警，独立于共享业务缓存及完整版本可读资格。 */
@Service
public class ReadViewFreshnessService {
  private final JdbcTemplate jdbcTemplate;
  private final SyncFactPublicationStateService production;
  private final PlatformPermissionService permissions;
  private final ReadFallbackProperties properties;
  private final com.data.collection.platform.service.BusinessDateProvider dates;

  public ReadViewFreshnessService(JdbcTemplate jdbcTemplate, SyncFactPublicationStateService production,
      PlatformPermissionService permissions, ReadFallbackProperties properties,
      com.data.collection.platform.service.BusinessDateProvider dates) {
    this.jdbcTemplate = jdbcTemplate;
    this.production = production;
    this.permissions = permissions;
    this.properties = properties;
    this.dates = dates;
  }

  /** 从持久页面闭包构造固定数据选择，事实/数据集组合的业务日和规则使用同一契约。 */
  public ReadSelection selection(ReadView view) {
    List<FactReadQueryContext> facts = view.domains().values().stream().filter(PublishedReadContext.class::isInstance)
        .map(PublishedReadContext.class::cast).map(FactReadQueryContext.class::cast).toList();
    List<PublishedDatasetReadContext> datasets = view.domains().values().stream().filter(PublishedDatasetReadContext.class::isInstance)
        .map(PublishedDatasetReadContext.class::cast).toList();
    FactReadSelection factSelection = facts.isEmpty() ? null : new FactReadSelection(facts, view.businessDate());
    return datasets.isEmpty() ? java.util.Objects.requireNonNull(factSelection)
        : new DatasetReadSelection(datasets, factSelection, view.businessDate());
  }

  /**
   * 当前生产状态只影响提示；依赖阻塞、未发布根或构建在途都不能否定已经绑定的完整读取身份。
   * 24 小时只升级当前管理员告警；没有未结算问题时，即使内容很老也不会触发告警。
   */
  public ReadViewMetadata metadata(ReadView view, AuthUserResponse caller) {
    AuthUserResponse currentUser = permissions.refreshUserPermissions(caller);
    boolean administrator = currentUser.authenticated()
        && (currentUser.roleCodes().contains("ADMIN") || currentUser.roleCodes().contains("SUPER_ADMIN"));
    long pending = 0;
    boolean updating = false;
    boolean attention = false;
    boolean newer = !view.businessDate().equals(java.util.Objects.requireNonNull(dates.businessDate()));
    boolean escalated = false;
    List<String> details = new ArrayList<>();
    for (var entry : view.domains().entrySet()) {
      var key = entry.getKey();
      var states = jdbcTemplate.query("""
          select domain.id, domain.current_publication_id,
            greatest(publication.published_at, domain.last_successful_verification_at),
            domain.unsettled_since,
            exists(select 1 from read_publications candidate where candidate.domain_id = domain.id
              and candidate.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')) as active_candidate,
            (select candidate.abort_reason from read_publications candidate where candidate.domain_id = domain.id
              and candidate.state = 'ABORTED'
              and candidate.created_at > greatest(publication.published_at, domain.last_successful_verification_at)
              order by candidate.created_at desc limit 1) as last_failure
            from read_publication_domains domain join read_publications publication on publication.id = domain.current_publication_id
           where domain.producer_kind = ? and domain.source_instance = ? and domain.fact_type = ? and domain.table_key = ?
          """, (rs, row) -> new DomainProgress(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
              rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(),
              rs.getBoolean(5), rs.getString(6)), key.producerKind().name(), key.sourceInstance(), key.factType(), key.tableKey());
      if (states.size() != 1) { throw ReadViewException.rebindRequired(view.viewId(), "读取来源已不存在"); }
      DomainProgress state = states.getFirst();
      newer |= !state.currentPublication().equals(entry.getValue().publicationId());
      boolean problem = state.activeCandidate();
      boolean blocked = state.lastFailure() != null;
      String reason = state.lastFailure();
      if (key.factType().equals("DATASET") && key.producerKind() != ReadPublicationProducerKind.MIRROR_TABLE) {
        var requests = jdbcTemplate.query("""
            select requested_revision - completed_revision, last_error from read_dataset_refresh_requests
             where producer_kind = ? and source_instance = ? and table_key = ''
            """, (rs, row) -> new RequestedProgress(rs.getLong(1), rs.getString(2)), key.producerKind().name(), key.sourceInstance());
        if (!requests.isEmpty()) {
          RequestedProgress request = requests.getFirst();
          // 这是合并输入批次数，不能冒充业务根数量；数据集未发布变更数量尚无精确值。
          problem |= request.pending() > 0;
          blocked |= request.pending() > 0 && request.failure() != null;
          if (request.pending() > 0 && request.failure() != null) { reason = request.failure(); }
        }
      }
      if (key.producerKind() == ReadPublicationProducerKind.GITLAB_FACT) {
        FactType type = FactType.valueOf(key.factType());
        var qualification = production.qualification(key.sourceInstance(), type);
        pending += production.countUnpublishedTargets(key.sourceInstance(), type);
        problem |= !qualification.currentFactReadable();
        blocked |= !qualification.savedSnapshotReadable();
        if (qualification.reason() != null) { reason = qualification.reason(); }
      }
      problem |= blocked;
      if (key.producerKind() == ReadPublicationProducerKind.MIRROR_TABLE) {
        var progress = jdbcTemplate.query("""
            select run.status, task.last_error from sync_run_table_states state
              join sync_run_table_tasks task on task.state_id = state.id
              join sync_runs run on run.id = task.run_id
             where state.source_instance = ? and state.mirror_table = ?
               and (run.status in ('SUBMITTED','QUEUED','RUNNING','RETRYING','PAUSED','CANCELLING')
                    or coalesce(task.finished_at, run.finished_at, run.created_at) > ?)
             order by run.id desc, task.id desc limit 1
            """, (rs, row) -> new MirrorProgress(rs.getString(1), rs.getString(2)),
            key.sourceInstance(), key.tableKey(), Timestamp.from(state.publishedAt()));
        if (!progress.isEmpty()) {
          String status = progress.getFirst().status();
          boolean failed = java.util.Set.of("FAILED", "CANCELLED", "TIMEOUT").contains(status);
          // 已完成镜像但尚未核验/发布读版本也属于更新窗口，不能提前披露为最新。
          problem = true;
          blocked |= failed;
          if (failed) { reason = progress.getFirst().error() == null ? "镜像表更新尚未完成" : progress.getFirst().error(); }
        }
      }
      if (key.producerKind() == ReadPublicationProducerKind.CAT_MIRROR) {
        var progress = jdbcTemplate.query("select status, message from bi_cat_sync_runs where started_at > ?"
            + " order by started_at desc, run_id desc limit 1", (rs, row) -> new MirrorProgress(rs.getString(1), rs.getString(2)),
            Timestamp.from(state.publishedAt()));
        if (!progress.isEmpty()) {
          String status = progress.getFirst().status();
          problem |= status.equals("RUNNING");
          boolean failed = status.equals("FAILED") || status.equals("PARTIAL_SUCCESS");
          blocked |= failed;
          if (failed) { reason = progress.getFirst().error(); }
        }
      }
      problem |= blocked;
      updating |= problem;
      attention |= blocked;
      if (administrator && reason != null) { details.add(reason); }
      if (problem) {
        Instant observed = state.unsettledSince();
        if (observed == null) {
          Instant first = jdbcTemplate.queryForObject("""
              select coalesce(min(event_time), current_timestamp) from (
                select created_at as event_time from read_publications where domain_id = ?
                  and created_at > ? and state <> 'PUBLISHED'
                union all select coalesce(finished_at, started_at, created_at)::timestamptz
                  from sync_runs where source_instance = ? and created_at > ?
                    and status in ('RUNNING', 'RETRYING', 'FAILED', 'CANCELLED', 'TIMEOUT')) events
              """, java.time.OffsetDateTime.class, state.domainId(), Timestamp.from(state.publishedAt()),
              key.sourceInstance(), Timestamp.from(state.publishedAt())).toInstant();
          jdbcTemplate.update("update read_publication_domains set unsettled_since = coalesce(unsettled_since, ?)"
              + " where id = ? and current_publication_id = ?", Timestamp.from(first), state.domainId(), state.currentPublication());
          observed = first;
        }
        escalated |= administrator && !Instant.now().isBefore(observed.plus(properties.getEscalateAfterHours(), ChronoUnit.HOURS));
      } else if (state.unsettledSince() != null) {
        jdbcTemplate.update("update read_publication_domains set unsettled_since = null where id = ? and current_publication_id = ?",
            state.domainId(), state.currentPublication());
      }
    }
    Instant cutoff = view.domains().values().stream().map(context -> context.dataAsOf()).min(Instant::compareTo).orElseThrow();
    ReadViewMetadata.SourceState state = attention ? ReadViewMetadata.SourceState.ATTENTION
        : updating ? ReadViewMetadata.SourceState.UPDATING : newer ? ReadViewMetadata.SourceState.NEWER_AVAILABLE : ReadViewMetadata.SourceState.CURRENT;
    return new ReadViewMetadata(view.viewId(), view.pageKey(), view.scopeKey(), selection(view).sourceVersion(),
        view.businessDate(), view.expiresAt(), cutoff, pending, state,
        administrator && !details.isEmpty() ? String.join("；", details) : null, escalated);
  }

  private record DomainProgress(UUID domainId, UUID currentPublication, Instant publishedAt,
      Instant unsettledSince, boolean activeCandidate, String lastFailure) {}
  private record RequestedProgress(long pending, String failure) {}
  private record MirrorProgress(String status, String error) {}
}
