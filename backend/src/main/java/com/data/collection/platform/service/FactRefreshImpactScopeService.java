package com.data.collection.platform.service;

import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FactRefreshImpactScopeService {
  private static final int MAX_PRECISE_TARGETS = 200;

  private final JdbcTemplate jdbcTemplate;
  private final SyncRunTableTaskMapper tableTaskMapper;

  public FactRefreshImpactScopeService(JdbcTemplate jdbcTemplate, SyncRunTableTaskMapper tableTaskMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.tableTaskMapper = tableTaskMapper;
  }

  public ImpactScope resolve(Long mirrorRunId, String sourceInstance, String factType) {
    if (mirrorRunId == null || factType == null || factType.isBlank()) {
      return ImpactScope.fallback();
    }
    List<SyncRunTableTask> tasks =
        tableTaskMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableTask>()
                .eq(SyncRunTableTask::getRunId, mirrorRunId)
                .eq(SyncRunTableTask::getStatus, SyncRunStatus.SUCCESS)
                .gt(SyncRunTableTask::getRowsApplied, 0L));
    if (tasks == null || tasks.isEmpty()) {
      return ImpactScope.empty();
    }
    String normalizedFactType = factType.trim().toUpperCase(Locale.ROOT);
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    return switch (normalizedFactType) {
      case "ISSUE" -> resolveIssueScope(tasks, normalizedSource);
      case "MERGE_REQUEST" -> resolveMergeRequestScope(tasks, normalizedSource);
      default -> ImpactScope.fallback();
    };
  }

  private ImpactScope resolveIssueScope(List<SyncRunTableTask> tasks, String sourceInstance) {
    Set<Target> targets = new LinkedHashSet<>();
    for (SyncRunTableTask task : tasks) {
      String table = normalizeTable(task.getSourceTable());
      if (List.of("projects", "users", "labels", "milestones").contains(table)) {
        return ImpactScope.fallback();
      }
      switch (table) {
        case "issues" -> addIssueTargetsFromIssues(task, sourceInstance, targets);
        case "notes" -> addIssueTargetsFromNotes(task, sourceInstance, targets);
        case "label_links" -> addIssueTargetsFromLabelLinks(task, sourceInstance, targets);
        case "issue_assignees" -> addIssueTargetsFromIssueAssignees(task, sourceInstance, targets);
        default -> {
          // Tables unrelated to issue facts can be ignored for ISSUE tasks.
        }
      }
      if (targets.size() > MAX_PRECISE_TARGETS) {
        return ImpactScope.fallback();
      }
    }
    return ImpactScope.precise(List.copyOf(targets));
  }

  private ImpactScope resolveMergeRequestScope(List<SyncRunTableTask> tasks, String sourceInstance) {
    Set<Target> targets = new LinkedHashSet<>();
    for (SyncRunTableTask task : tasks) {
      String table = normalizeTable(task.getSourceTable());
      if (List.of("projects", "users", "labels", "namespaces").contains(table)) {
        return ImpactScope.fallback();
      }
      switch (table) {
        case "merge_requests" -> addMergeRequestTargetsFromMergeRequests(task, sourceInstance, targets);
        case "merge_request_metrics" -> addMergeRequestTargetsFromMergeRequestMetrics(task, sourceInstance, targets);
        case "merge_request_reviewers", "merge_request_assignees" ->
            addMergeRequestTargetsFromJoinTable(task, sourceInstance, table, targets);
        case "notes" -> addMergeRequestTargetsFromNotes(task, sourceInstance, targets);
        case "label_links" -> addMergeRequestTargetsFromLabelLinks(task, sourceInstance, targets);
        default -> {
          // Tables unrelated to merge request facts can be ignored for MERGE_REQUEST tasks.
        }
      }
      if (targets.size() > MAX_PRECISE_TARGETS) {
        return ImpactScope.fallback();
      }
    }
    return ImpactScope.precise(List.copyOf(targets));
  }

  private void addIssueTargetsFromIssues(SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String issues = quoteMirrorTable("issues", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct project_id, iid
              from %s
             where mirror_task_id = ?
               and coalesce(mirror_deleted, false) = false
               and project_id is not null
               and iid is not null
            """.formatted(issues),
        task.getId()));
  }

  private void addIssueTargetsFromNotes(SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String notes = quoteMirrorTable("notes", sourceInstance);
    String issues = quoteMirrorTable("issues", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct i.project_id, i.iid
              from %s n
              join %s i
                on i.id = n.noteable_id
               and coalesce(i.mirror_deleted, false) = false
             where n.mirror_task_id = ?
               and coalesce(n.mirror_deleted, false) = false
               and n.noteable_type = 'Issue'
               and i.project_id is not null
               and i.iid is not null
            """.formatted(notes, issues),
        task.getId()));
  }

  private void addIssueTargetsFromLabelLinks(SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String labelLinks = quoteMirrorTable("label_links", sourceInstance);
    String issues = quoteMirrorTable("issues", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct i.project_id, i.iid
              from %s ll
              join %s i
                on i.id = ll.target_id
               and coalesce(i.mirror_deleted, false) = false
             where ll.mirror_task_id = ?
               and coalesce(ll.mirror_deleted, false) = false
               and ll.target_type = 'Issue'
               and i.project_id is not null
               and i.iid is not null
            """.formatted(labelLinks, issues),
        task.getId()));
  }

  private void addIssueTargetsFromIssueAssignees(SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String assignees = quoteMirrorTable("issue_assignees", sourceInstance);
    String issues = quoteMirrorTable("issues", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct i.project_id, i.iid
              from %s ia
              join %s i
                on i.id = ia.issue_id
               and coalesce(i.mirror_deleted, false) = false
             where ia.mirror_task_id = ?
               and coalesce(ia.mirror_deleted, false) = false
               and i.project_id is not null
               and i.iid is not null
            """.formatted(assignees, issues),
        task.getId()));
  }

  private void addMergeRequestTargetsFromMergeRequests(
      SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String mergeRequests = quoteMirrorTable("merge_requests", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct target_project_id as project_id, iid
              from %s
             where mirror_task_id = ?
               and coalesce(mirror_deleted, false) = false
               and target_project_id is not null
               and iid is not null
            """.formatted(mergeRequests),
        task.getId()));
  }

  private void addMergeRequestTargetsFromMergeRequestMetrics(
      SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String metrics = quoteMirrorTable("merge_request_metrics", sourceInstance);
    String mergeRequests = quoteMirrorTable("merge_requests", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct mr.target_project_id as project_id, mr.iid
              from %s m
              join %s mr
                on mr.id = m.merge_request_id
               and coalesce(mr.mirror_deleted, false) = false
             where m.mirror_task_id = ?
               and coalesce(m.mirror_deleted, false) = false
               and mr.target_project_id is not null
               and mr.iid is not null
            """.formatted(metrics, mergeRequests),
        task.getId()));
  }

  private void addMergeRequestTargetsFromJoinTable(
      SyncRunTableTask task, String sourceInstance, String sourceTable, Set<Target> targets) {
    String joinTable = quoteMirrorTable(sourceTable, sourceInstance);
    String mergeRequests = quoteMirrorTable("merge_requests", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct mr.target_project_id as project_id, mr.iid
              from %s jt
              join %s mr
                on mr.id = jt.merge_request_id
               and coalesce(mr.mirror_deleted, false) = false
             where jt.mirror_task_id = ?
               and coalesce(jt.mirror_deleted, false) = false
               and mr.target_project_id is not null
               and mr.iid is not null
            """.formatted(joinTable, mergeRequests),
        task.getId()));
  }

  private void addMergeRequestTargetsFromNotes(SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String notes = quoteMirrorTable("notes", sourceInstance);
    String mergeRequests = quoteMirrorTable("merge_requests", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct mr.target_project_id as project_id, mr.iid
              from %s n
              join %s mr
                on mr.id = n.noteable_id
               and coalesce(mr.mirror_deleted, false) = false
             where n.mirror_task_id = ?
               and coalesce(n.mirror_deleted, false) = false
               and n.noteable_type = 'MergeRequest'
               and mr.target_project_id is not null
               and mr.iid is not null
            """.formatted(notes, mergeRequests),
        task.getId()));
  }

  private void addMergeRequestTargetsFromLabelLinks(
      SyncRunTableTask task, String sourceInstance, Set<Target> targets) {
    String labelLinks = quoteMirrorTable("label_links", sourceInstance);
    String mergeRequests = quoteMirrorTable("merge_requests", sourceInstance);
    targets.addAll(queryTargets(
        """
            select distinct mr.target_project_id as project_id, mr.iid
              from %s ll
              join %s mr
                on mr.id = ll.target_id
               and coalesce(mr.mirror_deleted, false) = false
             where ll.mirror_task_id = ?
               and coalesce(ll.mirror_deleted, false) = false
               and ll.target_type = 'MergeRequest'
               and mr.target_project_id is not null
               and mr.iid is not null
            """.formatted(labelLinks, mergeRequests),
        task.getId()));
  }

  private List<Target> queryTargets(String sql, Long taskId) {
    if (taskId == null) {
      return List.of();
    }
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> new Target(rs.getLong("project_id"), rs.getLong("iid")),
        taskId);
  }

  private String quoteMirrorTable(String sourceTable, String sourceInstance) {
    return quoteIdentifier(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable, sourceInstance));
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private String normalizeTable(String sourceTable) {
    if (sourceTable == null || sourceTable.isBlank()) {
      return "";
    }
    return sourceTable.trim().toLowerCase(Locale.ROOT);
  }

  public record ImpactScope(boolean fallbackRequired, List<Target> targets) {
    static ImpactScope precise(List<Target> targets) {
      return new ImpactScope(false, targets == null ? List.of() : targets);
    }

    static ImpactScope empty() {
      return precise(List.of());
    }

    static ImpactScope fallback() {
      return new ImpactScope(true, List.of());
    }
  }

  public record Target(Long projectId, Long iid) {
  }
}
