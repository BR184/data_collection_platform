package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 延期标签写回队列。
 *
 * <p>作业行保留原唯一业务键，并追加本轮来源绑定与领取身份。领取原子生成令牌并推进领取修订；
 * 续租与结算同时校验 id、owner、令牌、领取修订和任务修订，更新零行即表示所有权或输入已经变化，
 * 旧 worker 不得再改写该行。
 */
@Service
@Slf4j
public class CustomerIssueDelayLabelWritebackQueueService {
  static final String STATUS_PENDING = "PENDING";
  static final String STATUS_RUNNING = "RUNNING";
  static final String STATUS_RETRY_WAIT = "RETRY_WAIT";
  static final String STATUS_SUCCEEDED = "SUCCEEDED";
  static final String STATUS_SKIPPED = "SKIPPED";
  static final String STATUS_DEAD = "DEAD";
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final int DEFAULT_MAX_ATTEMPTS = 10;
  private static final List<Integer> BACKOFF_MINUTES = List.of(1, 5, 15, 30, 60);
  private static final String CLAIM_FENCE =
      """
       id = ? and status = 'RUNNING' and lease_owner = ? and claim_token = ?
       and claim_revision = ? and task_revision = ? and lease_until >= current_timestamp
      """;

  private final JdbcTemplate jdbcTemplate;
  private final CustomerIssueDelayLabelWritebackPlanner planner;

  public CustomerIssueDelayLabelWritebackQueueService(
      JdbcTemplate jdbcTemplate,
      CustomerIssueDelayLabelWritebackPlanner planner) {
    this.jdbcTemplate = jdbcTemplate;
    this.planner = planner;
  }

  /**
   * 按 {@code issue_id} 有界分页登记本轮候选。
   *
   * <p>调用方必须位于"读取当前事实与真实标签、登记作业、推进游标"同一事务内，保证一页的输入与
   * 游标一致提交。作业携带本轮镜像、授权修订与授权事实版本，worker 执行前据此复核。
   *
   * @param config 当前数据源配置
   * @param authorizationRevision 本轮授权修订
   * @param requiredFactVersion 本轮授权事实版本
   * @param mirrorRunId 本轮前置镜像运行号
   * @param afterIssueId 上一页最后一个 issue_id；{@code null} 表示从头开始
   * @param limit 本页最大行数
   * @return 本页登记结果
   */
  public PageResult enqueuePage(
      GitlabSyncConfig config,
      long authorizationRevision,
      long requiredFactVersion,
      Long mirrorRunId,
      Long afterIssueId,
      int limit) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<IssueFact> candidates = loadCandidates(sourceInstance, afterIssueId, Math.max(1, limit));
    int enqueued = 0;
    for (IssueFact candidate : candidates) {
      CustomerIssueDelayLabelWritebackPlan plan = planner.plan(candidate);
      if (!plan.hasChanges()) {
        continue;
      }
      upsert(
          plan,
          config.getId(),
          authorizationRevision,
          requiredFactVersion,
          mirrorRunId,
          DEFAULT_MAX_ATTEMPTS);
      enqueued++;
    }
    Long lastIssueId =
        candidates.isEmpty() ? afterIssueId : candidates.getLast().getIssueId();
    return new PageResult(
        enqueued, lastIssueId, candidates.size() >= Math.max(1, limit));
  }

  public Optional<IssueFact> loadCandidate(String sourceInstance, Long projectId, Long issueIid) {
    if (projectId == null || issueIid == null) {
      return Optional.empty();
    }
    List<IssueFact> rows =
        jdbcTemplate.query(candidateSql() + " and f.project_id = ? and f.issue_iid = ?",
            (rs, rowNum) -> mapIssueFact(rs, sourceInstance),
            DEFAULT_SOURCE_SYSTEM,
            GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance),
            CustomerIssueScopeRules.LEGACY_CC_PRODUCT_PROJECT_ID,
            CustomerIssueScopeRules.CUSTOMER_ISSUE_START_DATE.atStartOfDay(),
            projectId,
            issueIid);
    return rows.stream().findFirst();
  }

  /**
   * 领取一个到期作业并原子生成本次领取身份。
   *
   * @param owner 当前 worker 标识
   * @param leaseSeconds 租约秒数，最小 1 秒
   * @return 已领取作业；没有到期作业时为 {@code null}
   */
  public CustomerIssueDelayLabelWritebackJob claimNext(String owner, int leaseSeconds) {
    String token = UUID.randomUUID().toString();
    List<CustomerIssueDelayLabelWritebackJob> rows =
        jdbcTemplate.query(
            """
            update customer_issue_delay_label_writeback_jobs
               set status = ?,
                   lease_owner = ?,
                   claim_token = ?,
                   claim_revision = claim_revision + 1,
                   lease_until = current_timestamp + (? * interval '1 second'),
                   updated_at = current_timestamp
             where id = (
               select id
                 from customer_issue_delay_label_writeback_jobs
                where (
                        status in (?, ?)
                        and next_run_at <= current_timestamp
                      )
                   or (
                        status = ?
                        and lease_until is not null
                        and lease_until < current_timestamp
                      )
                order by next_run_at asc, created_at asc, id asc
                for update skip locked
                limit 1
             )
             returning *
            """,
            (rs, rowNum) -> mapJob(rs),
            STATUS_RUNNING,
            owner,
            token,
            Math.max(1, leaseSeconds),
            STATUS_PENDING,
            STATUS_RETRY_WAIT,
            STATUS_RUNNING);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /**
   * 在发送前续租；令牌、领取修订或任务修订任一变化都表示本次执行已失去所有权。
   *
   * @return 是否仍持有该作业
   */
  public boolean renewLease(CustomerIssueDelayLabelWritebackJob job, int leaseSeconds) {
    int updated =
        jdbcTemplate.update(
            """
            update customer_issue_delay_label_writeback_jobs
               set lease_until = current_timestamp + (? * interval '1 second'),
                   updated_at = current_timestamp
             where
            """
                + CLAIM_FENCE,
            Math.max(1, leaseSeconds),
            job.id(),
            job.leaseOwner(),
            job.claimToken(),
            job.claimRevision(),
            job.taskRevision());
    return updated > 0;
  }

  public boolean markSucceeded(CustomerIssueDelayLabelWritebackJob job) {
    return finish(job, STATUS_SUCCEEDED, null, null);
  }

  public boolean markSkipped(CustomerIssueDelayLabelWritebackJob job, String message) {
    return finish(job, STATUS_SKIPPED, message, null);
  }

  public boolean markDead(
      CustomerIssueDelayLabelWritebackJob job, String message, Integer httpStatus) {
    return finish(job, STATUS_DEAD, message, httpStatus);
  }

  /**
   * 进行退避重试；达到最大次数时转为 {@code DEAD}。
   *
   * @return 本次结算是否仍属于当前执行
   */
  public boolean markRetry(
      CustomerIssueDelayLabelWritebackJob job, String message, Integer httpStatus) {
    int nextAttemptCount = job.attemptCount() + 1;
    if (nextAttemptCount >= job.maxAttempts()) {
      return markDead(job, message, httpStatus);
    }
    int updated =
        jdbcTemplate.update(
            """
            update customer_issue_delay_label_writeback_jobs
               set status = ?,
                   attempt_count = ?,
                   next_run_at = current_timestamp + (? * interval '1 minute'),
                   lease_owner = null,
                   lease_until = null,
                   claim_token = null,
                   last_error = ?,
                   last_http_status = ?,
                   updated_at = current_timestamp
             where
            """
                + CLAIM_FENCE,
            STATUS_RETRY_WAIT,
            nextAttemptCount,
            retryDelayMinutes(nextAttemptCount),
            trimError(message),
            httpStatus,
            job.id(),
            job.leaseOwner(),
            job.claimToken(),
            job.claimRevision(),
            job.taskRevision());
    return updated > 0;
  }

  /**
   * 等待新的来源授权或收敛，不计入 HTTP 失败次数。
   *
   * @return 本次等待是否仍属于当前执行
   */
  public boolean defer(CustomerIssueDelayLabelWritebackJob job, String message, LocalDateTime nextRunAt) {
    int updated =
        jdbcTemplate.update(
            """
            update customer_issue_delay_label_writeback_jobs
               set status = ?,
                   next_run_at = ?,
                   lease_owner = null,
                   lease_until = null,
                   claim_token = null,
                   last_error = ?,
                   updated_at = current_timestamp
             where
            """
                + CLAIM_FENCE,
            STATUS_PENDING,
            Timestamp.valueOf(nextRunAt),
            trimError(message),
            job.id(),
            job.leaseOwner(),
            job.claimToken(),
            job.claimRevision(),
            job.taskRevision());
    return updated > 0;
  }

  int retryDelayMinutes(int attemptCount) {
    if (attemptCount <= 0) {
      return BACKOFF_MINUTES.getFirst();
    }
    return BACKOFF_MINUTES.get(Math.min(attemptCount - 1, BACKOFF_MINUTES.size() - 1));
  }

  String candidateSql() {
    return """
        select f.*,
               coalesce(current_labels.label_names, '') as current_label_names
          from issue_fact f
          join ods_gitlab_issues source_issue
            on source_issue.project_id = f.project_id
           and source_issue.iid = f.issue_iid
           and coalesce(source_issue.mirror_deleted, false) = false
          left join lateral (
            select string_agg(label.title, ', ' order by label.title) as label_names
              from ods_gitlab_label_links label_link
              join ods_gitlab_labels label
                on label.id = label_link.label_id
               and coalesce(label.mirror_deleted, false) = false
             where coalesce(label_link.mirror_deleted, false) = false
               and label_link.target_type = 'Issue'
               and label_link.target_id = source_issue.id
          ) current_labels on true
         where f.source_system = ?
           and f.source_instance = ?
           and f.deleted = false
           and f.project_id = ?
           and lower(coalesce(f.issue_state, 'opened')) <> 'closed'
           and f.created_at_source >= ?
           and f.issue_id is not null
           and coalesce(f.illegal_reason, '') not like '%GitLab接口报错%'
           and coalesce(f.illegal_reasons, '') not like '%GitLab接口报错%'
        """;
  }

  private List<IssueFact> loadCandidates(
      String sourceInstance, Long afterIssueId, int limit) {
    return jdbcTemplate.query(
        candidateSql()
            + " and f.issue_id > ? order by f.issue_id asc limit ?",
        (rs, rowNum) -> mapIssueFact(rs, sourceInstance),
        DEFAULT_SOURCE_SYSTEM,
        GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance),
        CustomerIssueScopeRules.LEGACY_CC_PRODUCT_PROJECT_ID,
        CustomerIssueScopeRules.CUSTOMER_ISSUE_START_DATE.atStartOfDay(),
        afterIssueId == null ? 0L : afterIssueId,
        limit);
  }

  private void upsert(
      CustomerIssueDelayLabelWritebackPlan plan,
      Long configId,
      long authorizationRevision,
      long requiredFactVersion,
      Long mirrorRunId,
      int maxAttempts) {
    IssueFact fact = plan.fact();
    jdbcTemplate.update(
        """
        insert into customer_issue_delay_label_writeback_jobs (
            source_instance,
            config_id,
            project_id,
            issue_iid,
            issue_id,
            desired_response_delayed,
            desired_resolve_delayed,
            current_label_names,
            add_labels,
            remove_labels,
            status,
            attempt_count,
            max_attempts,
            next_run_at,
            round_mirror_run_id,
            authorization_revision,
            required_fact_version,
            task_revision,
            lease_owner,
            lease_until,
            claim_token,
            last_error,
            last_http_status,
            finished_at,
            created_at,
            updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, current_timestamp, ?, ?, ?, 1, null, null, null, null, null, null, current_timestamp, current_timestamp)
        on conflict (source_instance, project_id, issue_iid) do update
          set config_id = excluded.config_id,
              issue_id = excluded.issue_id,
              desired_response_delayed = excluded.desired_response_delayed,
              desired_resolve_delayed = excluded.desired_resolve_delayed,
              current_label_names = excluded.current_label_names,
              add_labels = excluded.add_labels,
              remove_labels = excluded.remove_labels,
              round_mirror_run_id = excluded.round_mirror_run_id,
              authorization_revision = excluded.authorization_revision,
              required_fact_version = excluded.required_fact_version,
              task_revision = customer_issue_delay_label_writeback_jobs.task_revision + 1,
              status = case
                  when customer_issue_delay_label_writeback_jobs.status = 'DEAD'
                    then customer_issue_delay_label_writeback_jobs.status
                  when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                       and customer_issue_delay_label_writeback_jobs.lease_until >= current_timestamp
                    then customer_issue_delay_label_writeback_jobs.status
                  else excluded.status
                end,
              attempt_count = case
                  when customer_issue_delay_label_writeback_jobs.status = 'DEAD'
                    then customer_issue_delay_label_writeback_jobs.attempt_count
                  when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                       and customer_issue_delay_label_writeback_jobs.lease_until >= current_timestamp
                    then customer_issue_delay_label_writeback_jobs.attempt_count
                  else 0
                end,
              max_attempts = excluded.max_attempts,
              next_run_at = case
                  when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                       and customer_issue_delay_label_writeback_jobs.lease_until >= current_timestamp
                    then customer_issue_delay_label_writeback_jobs.next_run_at
                  else current_timestamp
                end,
              lease_owner = case
                  when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                       and customer_issue_delay_label_writeback_jobs.lease_until >= current_timestamp
                    then customer_issue_delay_label_writeback_jobs.lease_owner
                  else null
                end,
              lease_until = case
                  when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                       and customer_issue_delay_label_writeback_jobs.lease_until >= current_timestamp
                    then customer_issue_delay_label_writeback_jobs.lease_until
                  else null
                end,
              claim_token = case
                  when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                       and customer_issue_delay_label_writeback_jobs.lease_until >= current_timestamp
                    then customer_issue_delay_label_writeback_jobs.claim_token
                  else null
                end,
              last_error = case
                  when customer_issue_delay_label_writeback_jobs.status = 'DEAD'
                    then customer_issue_delay_label_writeback_jobs.last_error
                  else null
                end,
              last_http_status = case
                  when customer_issue_delay_label_writeback_jobs.status = 'DEAD'
                    then customer_issue_delay_label_writeback_jobs.last_http_status
                  else null
                end,
              finished_at = null,
              updated_at = current_timestamp
        """,
        GitlabSourceInstanceSupport.normalizeSourceInstance(fact.getSourceInstance()),
        configId,
        fact.getProjectId(),
        fact.getIssueIid(),
        fact.getIssueId(),
        Boolean.TRUE.equals(fact.getResponseDelayed()),
        Boolean.TRUE.equals(fact.getResolveDelayed()),
        fact.getLabelNames(),
        joinLabels(plan.change().addLabels()),
        joinLabels(plan.change().removeLabels()),
        STATUS_PENDING,
        maxAttempts,
        mirrorRunId,
        authorizationRevision,
        requiredFactVersion);
  }

  private boolean finish(
      CustomerIssueDelayLabelWritebackJob job,
      String status,
      String message,
      Integer httpStatus) {
    int updated =
        jdbcTemplate.update(
            """
            update customer_issue_delay_label_writeback_jobs
               set status = ?,
                   lease_owner = null,
                   lease_until = null,
                   claim_token = null,
                   last_error = ?,
                   last_http_status = ?,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where
            """
                + CLAIM_FENCE,
            status,
            trimError(message),
            httpStatus,
            job.id(),
            job.leaseOwner(),
            job.claimToken(),
            job.claimRevision(),
            job.taskRevision());
    return updated > 0;
  }

  private IssueFact mapIssueFact(ResultSet rs, String sourceInstance) throws SQLException {
    IssueFact fact = new IssueFact();
    fact.setSourceSystem(text(rs, "source_system", DEFAULT_SOURCE_SYSTEM));
    fact.setSourceInstance(text(rs, "source_instance", GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance)));
    fact.setProjectId(rs.getLong("project_id"));
    fact.setProjectName(text(rs, "project_name", null));
    fact.setIssueId(nullableLong(rs, "issue_id"));
    fact.setIssueIid(nullableLong(rs, "issue_iid"));
    fact.setTitle(text(rs, "title", null));
    fact.setIssueState(text(rs, "issue_state", null));
    fact.setCreatedAtSource(toLocalDateTime(rs.getTimestamp("created_at_source")));
    fact.setLabelNames(text(rs, "current_label_names", ""));
    fact.setIllegalReason(text(rs, "illegal_reason", null));
    fact.setIllegalReasons(text(rs, "illegal_reasons", null));
    fact.setResponseDelayed(rs.getBoolean("is_response_delayed"));
    fact.setResolveDelayed(rs.getBoolean("is_resolve_delayed"));
    return fact;
  }

  private CustomerIssueDelayLabelWritebackJob mapJob(ResultSet rs) throws SQLException {
    return new CustomerIssueDelayLabelWritebackJob(
        rs.getLong("id"),
        rs.getString("source_instance"),
        nullableLong(rs, "config_id"),
        rs.getLong("project_id"),
        rs.getLong("issue_iid"),
        nullableLong(rs, "issue_id"),
        rs.getBoolean("desired_response_delayed"),
        rs.getBoolean("desired_resolve_delayed"),
        rs.getString("current_label_names"),
        splitLabels(rs.getString("add_labels")),
        splitLabels(rs.getString("remove_labels")),
        rs.getString("status"),
        rs.getInt("attempt_count"),
        rs.getInt("max_attempts"),
        toLocalDateTime(rs.getTimestamp("next_run_at")),
        rs.getString("lease_owner"),
        toLocalDateTime(rs.getTimestamp("lease_until")),
        rs.getString("last_error"),
        (Integer) rs.getObject("last_http_status"),
        nullableLong(rs, "round_mirror_run_id"),
        nullableLong(rs, "authorization_revision"),
        nullableLong(rs, "required_fact_version"),
        rs.getString("claim_token"),
        rs.getLong("claim_revision"),
        rs.getLong("task_revision"));
  }

  private String joinLabels(List<String> labels) {
    return String.join(", ", labels == null ? List.of() : labels);
  }

  private List<String> splitLabels(String labels) {
    if (!StringUtils.hasText(labels)) {
      return List.of();
    }
    return Arrays.stream(labels.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .toList();
  }

  private String text(ResultSet rs, String column, String fallback) throws SQLException {
    String value = rs.getString(column);
    return StringUtils.hasText(value) ? value : fallback;
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    Long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private String trimError(String message) {
    if (!StringUtils.hasText(message)) {
      return null;
    }
    String trimmed = message.trim().replaceAll("\\s+", " ");
    return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000);
  }

  /** 一页候选登记结果。 */
  public record PageResult(int enqueued, Long lastIssueId, boolean hasMore) {}
}
