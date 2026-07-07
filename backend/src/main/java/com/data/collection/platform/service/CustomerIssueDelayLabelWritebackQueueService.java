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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
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

  private final JdbcTemplate jdbcTemplate;
  private final CustomerIssueDelayLabelWritebackPlanner planner;

  public CustomerIssueDelayLabelWritebackQueueService(
      JdbcTemplate jdbcTemplate,
      CustomerIssueDelayLabelWritebackPlanner planner) {
    this.jdbcTemplate = jdbcTemplate;
    this.planner = planner;
  }

  public int enqueueCandidates(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<IssueFact> candidates = loadCandidates(sourceInstance);
    int enqueued = 0;
    for (IssueFact candidate : candidates) {
      CustomerIssueDelayLabelWritebackPlan plan = planner.plan(candidate);
      if (!plan.hasChanges()) {
        continue;
      }
      upsert(plan, DEFAULT_MAX_ATTEMPTS);
      enqueued++;
    }
    return enqueued;
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

  public CustomerIssueDelayLabelWritebackJob claimNext(String owner, int leaseSeconds) {
    List<CustomerIssueDelayLabelWritebackJob> rows =
        jdbcTemplate.query(
            """
            update customer_issue_delay_label_writeback_jobs
               set status = ?,
                   lease_owner = ?,
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
            Math.max(1, leaseSeconds),
            STATUS_PENDING,
            STATUS_RETRY_WAIT,
            STATUS_RUNNING);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void markSucceeded(Long id) {
    finish(id, STATUS_SUCCEEDED, null, null);
  }

  public void markSkipped(Long id, String message) {
    finish(id, STATUS_SKIPPED, message, null);
  }

  public void markDead(Long id, String message, Integer httpStatus) {
    finish(id, STATUS_DEAD, message, httpStatus);
  }

  public void markRetry(Long id, int attemptCount, int maxAttempts, String message, Integer httpStatus) {
    int nextAttemptCount = attemptCount + 1;
    if (nextAttemptCount >= maxAttempts) {
      markDead(id, message, httpStatus);
      return;
    }
    jdbcTemplate.update(
        """
        update customer_issue_delay_label_writeback_jobs
           set status = ?,
               attempt_count = ?,
               next_run_at = current_timestamp + (? * interval '1 minute'),
               lease_owner = null,
               lease_until = null,
               last_error = ?,
               last_http_status = ?,
               updated_at = current_timestamp
         where id = ?
        """,
        STATUS_RETRY_WAIT,
        nextAttemptCount,
        retryDelayMinutes(nextAttemptCount),
        trimError(message),
        httpStatus,
        id);
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
               coalesce(current_labels.label_names, f.label_names, '') as current_label_names
          from issue_fact f
          left join ods_gitlab_issues source_issue
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
               and label_link.target_id = coalesce(source_issue.id, f.issue_id)
          ) current_labels on true
         where f.source_system = ?
           and f.source_instance = ?
           and f.deleted = false
           and f.project_id = ?
           and lower(coalesce(f.issue_state, 'opened')) <> 'closed'
           and f.created_at_source >= ?
           and coalesce(f.illegal_reason, '') not like '%GitLab接口报错%'
           and coalesce(f.illegal_reasons, '') not like '%GitLab接口报错%'
        """;
  }

  private List<IssueFact> loadCandidates(String sourceInstance) {
    return jdbcTemplate.query(
        candidateSql(),
        (rs, rowNum) -> mapIssueFact(rs, sourceInstance),
        DEFAULT_SOURCE_SYSTEM,
        GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance),
        CustomerIssueScopeRules.LEGACY_CC_PRODUCT_PROJECT_ID,
        CustomerIssueScopeRules.CUSTOMER_ISSUE_START_DATE.atStartOfDay());
  }

  private void upsert(CustomerIssueDelayLabelWritebackPlan plan, int maxAttempts) {
    IssueFact fact = plan.fact();
    jdbcTemplate.update(
        """
        insert into customer_issue_delay_label_writeback_jobs (
            source_instance,
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
            lease_owner,
            lease_until,
            last_error,
            last_http_status,
            finished_at,
            created_at,
            updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, current_timestamp, null, null, null, null, null, current_timestamp, current_timestamp)
        on conflict (source_instance, project_id, issue_iid)
        do update set
            issue_id = excluded.issue_id,
            desired_response_delayed = excluded.desired_response_delayed,
            desired_resolve_delayed = excluded.desired_resolve_delayed,
            current_label_names = excluded.current_label_names,
            add_labels = excluded.add_labels,
            remove_labels = excluded.remove_labels,
            status = case
                when customer_issue_delay_label_writeback_jobs.status = 'DEAD' then customer_issue_delay_label_writeback_jobs.status
                when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                     and customer_issue_delay_label_writeback_jobs.lease_until >= current_timestamp
                  then customer_issue_delay_label_writeback_jobs.status
                else excluded.status
            end,
            attempt_count = case
                when customer_issue_delay_label_writeback_jobs.status in ('DEAD', 'RUNNING')
                  then customer_issue_delay_label_writeback_jobs.attempt_count
                else 0
            end,
            max_attempts = excluded.max_attempts,
            next_run_at = case
                when customer_issue_delay_label_writeback_jobs.status in ('DEAD', 'RUNNING')
                  then customer_issue_delay_label_writeback_jobs.next_run_at
                else current_timestamp
            end,
            lease_owner = case
                when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                  then customer_issue_delay_label_writeback_jobs.lease_owner
                else null
            end,
            lease_until = case
                when customer_issue_delay_label_writeback_jobs.status = 'RUNNING'
                  then customer_issue_delay_label_writeback_jobs.lease_until
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
        fact.getProjectId(),
        fact.getIssueIid(),
        fact.getIssueId(),
        Boolean.TRUE.equals(fact.getResponseDelayed()),
        Boolean.TRUE.equals(fact.getResolveDelayed()),
        fact.getLabelNames(),
        joinLabels(plan.change().addLabels()),
        joinLabels(plan.change().removeLabels()),
        STATUS_PENDING,
        maxAttempts);
  }

  private void finish(Long id, String status, String message, Integer httpStatus) {
    jdbcTemplate.update(
        """
        update customer_issue_delay_label_writeback_jobs
           set status = ?,
               lease_owner = null,
               lease_until = null,
               last_error = ?,
               last_http_status = ?,
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where id = ?
        """,
        status,
        trimError(message),
        httpStatus,
        id);
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
        (Integer) rs.getObject("last_http_status"));
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
}
