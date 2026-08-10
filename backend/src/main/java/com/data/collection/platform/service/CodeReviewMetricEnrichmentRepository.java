package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
class CodeReviewMetricEnrichmentRepository {
  private static final String CANDIDATE_SELECT = """
      with recursive namespace_paths as (
        select id, parent_id, path::text as full_path
          from ods_gitlab_namespaces
         where parent_id is null
           and coalesce(mirror_deleted, false) = false
        union all
        select child.id,
               child.parent_id,
               parent.full_path || '/' || child.path
          from ods_gitlab_namespaces child
          join namespace_paths parent on parent.id = child.parent_id
         where coalesce(child.mirror_deleted, false) = false
      )
      select mr.id as merge_request_id,
             mr.target_project_id as project_id,
             mr.iid as merge_request_iid,
             mr.title,
             coalesce(namespace.full_path || '/' || project.path, project.path) as project_path,
             coalesce(mr.updated_at, mr.created_at) as source_updated_at
        from ods_gitlab_merge_requests mr
        left join ods_gitlab_projects project
          on project.id = mr.target_project_id
         and coalesce(project.mirror_deleted, false) = false
        left join namespace_paths namespace on namespace.id = project.namespace_id
       where coalesce(mr.mirror_deleted, false) = false
         and mr.target_project_id = 9
         and mr.target_project_id is not null
         and mr.iid is not null
      """;

  private final JdbcTemplate jdbcTemplate;
  private final GitlabMirrorProperties properties;

  CodeReviewMetricEnrichmentRepository(
      JdbcTemplate jdbcTemplate, GitlabMirrorProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  boolean sourceTablesAvailable() {
    Boolean available = jdbcTemplate.queryForObject(
        """
        select to_regclass('public.ods_gitlab_merge_requests') is not null
           and to_regclass('public.ods_gitlab_projects') is not null
           and to_regclass('public.ods_gitlab_namespaces') is not null
        """,
        Boolean.class);
    return Boolean.TRUE.equals(available);
  }

  int enqueueHistorical(String sourceInstance, int limit) {
    ensureState(sourceInstance);
    long cursor = jdbcTemplate.queryForObject(
        "select historical_cursor_merge_request_id from code_review_metric_enrichment_states where source_instance = ?",
        Long.class,
        sourceInstance);
    List<Candidate> candidates = loadCandidates(
        CANDIDATE_SELECT + " and mr.id > ? order by mr.id limit ?",
        sourceInstance,
        cursor,
        Math.max(1, limit));
    if (candidates.isEmpty()) {
      jdbcTemplate.update(
          """
          update code_review_metric_enrichment_states
             set historical_completed_at = coalesce(historical_completed_at, current_timestamp),
                 updated_at = current_timestamp
           where source_instance = ?
          """,
          sourceInstance);
      return 0;
    }
    candidates.forEach(this::upsertCandidate);
    long nextCursor = candidates.getLast().mergeRequestId();
    jdbcTemplate.update(
        """
        update code_review_metric_enrichment_states
           set historical_cursor_merge_request_id = ?,
               historical_completed_at = null,
               updated_at = current_timestamp
         where source_instance = ?
        """,
        nextCursor,
        sourceInstance);
    return candidates.size();
  }

  int enqueueNextCompletedRun(String sourceInstance, int limit) {
    ensureState(sourceInstance);
    EnrichmentState state = loadState(sourceInstance);
    Long runId = state.activeSyncRunId();
    long runCursor = state.activeRunCursorMergeRequestId();
    if (runId == null) {
      List<Long> runIds = loadNextCompletedRunIds(sourceInstance, state.lastProcessedSyncRunId());
      if (runIds.isEmpty()) {
        return 0;
      }
      runId = runIds.getFirst();
      runCursor = 0L;
      jdbcTemplate.update(
          """
          update code_review_metric_enrichment_states
             set active_sync_run_id = ?,
                 active_run_cursor_merge_request_id = 0,
                 updated_at = current_timestamp
           where source_instance = ?
          """,
          runId,
          sourceInstance);
    }
    String changedSql = CANDIDATE_SELECT + """
         and (
           mr.mirror_task_id in (select id from sync_run_table_tasks where run_id = ?)
           or exists (
             select 1
               from ods_gitlab_merge_request_metrics metric
              where metric.merge_request_id = mr.id
                and metric.mirror_task_id in (
                  select id from sync_run_table_tasks where run_id = ?
             )
           )
         )
         and mr.id > ?
       order by mr.id
       limit ?
      """;
    List<Candidate> candidates = loadCandidates(
        changedSql,
        sourceInstance,
        runId,
        runId,
        runCursor,
        Math.max(1, limit));
    if (candidates.isEmpty()) {
      completeActiveRun(sourceInstance, runId);
      return 0;
    }
    candidates.forEach(this::upsertCandidate);
    jdbcTemplate.update(
        """
        update code_review_metric_enrichment_states
           set active_run_cursor_merge_request_id = ?,
               updated_at = current_timestamp
         where source_instance = ?
           and active_sync_run_id = ?
        """,
        candidates.getLast().mergeRequestId(),
        sourceInstance,
        runId);
    return candidates.size();
  }

  List<EnrichmentClaim> claim(String sourceInstance, int limit) {
    return jdbcTemplate.query(
        """
        update code_review_external_metrics
           set enrichment_status = 'RUNNING',
               enrichment_attempts = case
                 when enrichment_attempts < 2147483647 then enrichment_attempts + 1
                 else enrichment_attempts
               end,
               enrichment_attempted_at = current_timestamp,
               enrichment_error = null,
               updated_at = current_timestamp
         where id in (
           select id
             from code_review_external_metrics
            where source_instance = ?
              and (
                  enrichment_status = 'PENDING'
               or (enrichment_status = 'RETRY'
                   and coalesce(enrichment_next_attempt_at, current_timestamp) <= current_timestamp)
               or (enrichment_status = 'RUNNING'
                   and enrichment_attempted_at < current_timestamp - interval '10 minutes')
            )
            order by coalesce(enrichment_next_attempt_at, timestamp 'epoch'), id
            for update skip locked
            limit ?
         )
        returning id, source_instance, project_id, merge_request_id,
                  merge_request_iid, project_path, enrichment_attempts
        """,
        (rs, rowNum) -> new EnrichmentClaim(
            rs.getLong("id"),
            rs.getString("source_instance"),
            rs.getLong("project_id"),
            rs.getLong("merge_request_id"),
            rs.getLong("merge_request_iid"),
            rs.getString("project_path"),
            rs.getInt("enrichment_attempts")),
        sourceInstance,
        Math.max(1, limit));
  }

  void markEnriched(
      Long id, int addedLines, int deletedLines, String rawPayload, String sourceUri) {
    String sourceSummary = "GitLab diff: " + sourceUri;
    if (sourceSummary.length() > 255) {
      sourceSummary = sourceSummary.substring(0, 255);
    }
    jdbcTemplate.update(
        """
        update code_review_external_metrics
           set added_lines = ?,
               deleted_lines = ?,
               source_summary = ?,
               raw_payload = ?,
               enrichment_status = 'ENRICHED',
               enrichment_error = null,
               enrichment_next_attempt_at = null,
               enrichment_succeeded_at = current_timestamp,
               updated_at = current_timestamp
         where id = ?
           and enrichment_status = 'RUNNING'
        """,
        addedLines,
        deletedLines,
        sourceSummary,
        rawPayload,
        id);
  }

  void markFailed(Long id, boolean retryable, String errorMessage) {
    String safeError = errorMessage == null ? "GitLab diff 补齐失败" : errorMessage;
    if (safeError.length() > 512) {
      safeError = safeError.substring(0, 512);
    }
    jdbcTemplate.update(
        """
        update code_review_external_metrics
           set enrichment_status = case
                 when ? then 'RETRY'
                 else 'FAILED'
               end,
               enrichment_error = ?,
               enrichment_next_attempt_at = case
                 when ? then
                   current_timestamp
                     + (least(3600, ? * power(2, least(greatest(enrichment_attempts - 1, 0), 20)))
                        * interval '1 second')
                 else null
               end,
               updated_at = current_timestamp
         where id = ?
           and enrichment_status = 'RUNNING'
        """,
        retryable,
        safeError,
        retryable,
        Math.max(1, properties.getCodeReviewMetricRetryBaseSeconds()),
        id);
  }

  List<EnrichedTarget> loadEnrichedTargets(String sourceInstance, int limit) {
    return jdbcTemplate.query(
        """
        select metric.id,
               metric.source_instance,
               metric.project_id,
               mr.id as merge_request_id,
               metric.merge_request_iid
          from code_review_external_metrics metric
          join ods_gitlab_merge_requests mr
            on mr.target_project_id = metric.project_id
           and mr.iid = metric.merge_request_iid
         where metric.source_instance = ?
           and metric.enrichment_status = 'ENRICHED'
         order by metric.id
         limit ?
        """,
        (rs, rowNum) -> new EnrichedTarget(
            rs.getLong("id"),
            rs.getString("source_instance"),
            rs.getLong("project_id"),
            rs.getLong("merge_request_id"),
            rs.getLong("merge_request_iid")),
        sourceInstance,
        Math.max(1, limit));
  }

  void markPublished(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    String placeholders = String.join(",", ids.stream().map(ignored -> "?").toList());
    jdbcTemplate.update(
        "update code_review_external_metrics set enrichment_status = 'SUCCESS', updated_at = current_timestamp "
            + "where enrichment_status = 'ENRICHED' and id in (" + placeholders + ")",
        ids.toArray());
  }

  private List<Candidate> loadCandidates(String sql, String sourceInstance, Object... args) {
    List<Object> queryArgs = new ArrayList<>(List.of(args));
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> new Candidate(
            sourceInstance,
            rs.getLong("project_id"),
            rs.getLong("merge_request_id"),
            rs.getLong("merge_request_iid"),
            rs.getString("project_path"),
            MergeRequestTitleFunctionParser.parse(rs.getString("title")),
            rs.getTimestamp("source_updated_at") == null
                ? null
                : rs.getTimestamp("source_updated_at").toLocalDateTime()),
        queryArgs.toArray());
  }

  private void upsertCandidate(Candidate candidate) {
    jdbcTemplate.update(
        """
        insert into code_review_external_metrics(
            source_instance, project_id, merge_request_id, merge_request_iid,
            project_path, function_name, metric_source_updated_at,
            enrichment_status, enrichment_version, enrichment_attempts,
            created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, 0, current_timestamp, current_timestamp)
        on conflict (source_instance, project_id, merge_request_iid)
        do update set
            merge_request_id = excluded.merge_request_id,
            project_path = excluded.project_path,
            function_name = excluded.function_name,
            metric_source_updated_at = excluded.metric_source_updated_at,
            enrichment_status = 'PENDING',
            enrichment_attempts = 0,
            enrichment_error = null,
            enrichment_next_attempt_at = null,
            updated_at = current_timestamp
        where code_review_external_metrics.metric_source_updated_at is distinct from excluded.metric_source_updated_at
           or code_review_external_metrics.project_path is distinct from excluded.project_path
           or code_review_external_metrics.function_name is distinct from excluded.function_name
        """,
        candidate.sourceInstance(),
        candidate.projectId(),
        candidate.mergeRequestId(),
        candidate.mergeRequestIid(),
        candidate.projectPath(),
        candidate.functionName(),
        candidate.sourceUpdatedAt());
  }

  private void ensureState(String sourceInstance) {
    jdbcTemplate.update(
        """
        insert into code_review_metric_enrichment_states(
            source_instance, last_processed_sync_run_id)
        select ?, coalesce(max(id), 0)
          from sync_runs
         where source_instance = ?
           and run_type in (
             'FULL_SYNC', 'INCREMENTAL_SYNC', 'TABLE_REFRESH',
             'SYSTEM_HOOK', 'FULL_COMPENSATION_SCAN', 'DELETE_RECONCILIATION'
           )
           and status in ('SUCCESS', 'PARTIAL_SUCCESS')
        on conflict (source_instance) do nothing
        """,
        sourceInstance,
        sourceInstance);
  }

  private EnrichmentState loadState(String sourceInstance) {
    return jdbcTemplate.queryForObject(
        """
        select last_processed_sync_run_id,
               active_sync_run_id,
               active_run_cursor_merge_request_id
          from code_review_metric_enrichment_states
         where source_instance = ?
        """,
        (rs, rowNum) -> new EnrichmentState(
            rs.getLong("last_processed_sync_run_id"),
            rs.getObject("active_sync_run_id", Long.class),
            rs.getLong("active_run_cursor_merge_request_id")),
        sourceInstance);
  }

  private List<Long> loadNextCompletedRunIds(String sourceInstance, long lastRunId) {
    return jdbcTemplate.queryForList(
        """
        select id
          from sync_runs
         where id > ?
           and source_instance = ?
           and run_type in (
             'FULL_SYNC', 'INCREMENTAL_SYNC', 'TABLE_REFRESH',
             'SYSTEM_HOOK', 'FULL_COMPENSATION_SCAN', 'DELETE_RECONCILIATION'
           )
           and status in ('SUCCESS', 'PARTIAL_SUCCESS')
         order by id
         limit 1
        """,
        Long.class,
        lastRunId,
        sourceInstance);
  }

  private void completeActiveRun(String sourceInstance, long runId) {
    jdbcTemplate.update(
        """
        update code_review_metric_enrichment_states
           set last_processed_sync_run_id = ?,
               active_sync_run_id = null,
               active_run_cursor_merge_request_id = 0,
               updated_at = current_timestamp
         where source_instance = ?
           and active_sync_run_id = ?
        """,
        runId,
        sourceInstance,
        runId);
  }

  record EnrichmentClaim(
      Long id,
      String sourceInstance,
      Long projectId,
      Long mergeRequestId,
      Long mergeRequestIid,
      String projectPath,
      int attempts) {}

  record EnrichedTarget(
      Long id,
      String sourceInstance,
      Long projectId,
      Long mergeRequestId,
      Long mergeRequestIid) {}

  private record EnrichmentState(
      long lastProcessedSyncRunId,
      Long activeSyncRunId,
      long activeRunCursorMergeRequestId) {}

  private record Candidate(
      String sourceInstance,
      Long projectId,
      Long mergeRequestId,
      Long mergeRequestIid,
      String projectPath,
      String functionName,
      LocalDateTime sourceUpdatedAt) {}
}
