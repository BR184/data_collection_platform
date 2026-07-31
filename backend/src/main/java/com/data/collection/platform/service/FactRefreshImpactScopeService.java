package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.service.sync.AuthoritativeRelationCatalog;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FactRefreshImpactScopeService {
  private static final int MAX_PRECISE_TARGETS = 200;

  private final JdbcTemplate jdbcTemplate;
  private final SyncRunTableTaskMapper tableTaskMapper;
  private final SyncRunMapper syncRunMapper;
  private final JsonUtils jsonUtils;

  public FactRefreshImpactScopeService(
      JdbcTemplate jdbcTemplate,
      SyncRunTableTaskMapper tableTaskMapper,
      SyncRunMapper syncRunMapper,
      JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.tableTaskMapper = tableTaskMapper;
    this.syncRunMapper = syncRunMapper;
    this.jsonUtils = jsonUtils;
  }

  /**
   * 从事实子运行的持久父子关系解析本次镜像变更影响范围。
   *
   * <p>{@code fact_build_tasks.run_id} 只表示事实任务归属；镜像影响来源只读取
   * {@code sync_runs.parent_run_id}。运行类型、配置或数据源不一致，以及已有镜像工作量却缺失表任务链时，
   * 视为发布链不变量破坏并显式失败，禁止退化为空影响成功。
   *
   * @param factRunId 当前 {@code FACT_REFRESH} 子运行 ID
   * @param configId 事实任务归属的配置 ID
   * @param sourceInstance 事实任务归属的数据源实例
   * @param factType 需要解析的事实类型
   * @return 精确目标、合法空范围或要求受控全量回退的影响范围
   */
  public ImpactScope resolve(
      Long factRunId,
      Long configId,
      String sourceInstance,
      String factType) {
    if (factRunId == null || configId == null) {
      throw new IllegalArgumentException("事实影响解析需要事实运行 ID 和配置 ID");
    }
    if (factType == null || factType.isBlank()) {
      throw new IllegalArgumentException("事实影响解析需要事实类型");
    }
    SyncRun mirrorRun = resolveMirrorRun(factRunId, configId, sourceInstance);
    List<SyncRunTableTask> allTasks =
        tableTaskMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableTask>()
                .eq(SyncRunTableTask::getRunId, mirrorRun.getId()));
    if (allTasks == null || allTasks.isEmpty()) {
      if (hasRecordedMirrorWork(mirrorRun)) {
        throw new IllegalStateException(
            "镜像父运行 " + mirrorRun.getId() + " 已记录处理结果，但表任务链缺失");
      }
      return ImpactScope.empty();
    }
    if (allTasks.stream()
        .filter(Objects::nonNull)
        .anyMatch(task -> !Objects.equals(task.getRunId(), mirrorRun.getId()))) {
      throw new IllegalStateException("镜像父运行的表任务归属不一致: " + mirrorRun.getId());
    }
    List<SyncRunTableTask> tasks =
        allTasks.stream()
            .filter(Objects::nonNull)
            .filter(task -> task.getStatus() == SyncRunStatus.SUCCESS)
            .filter(task -> task.getRowsApplied() != null && task.getRowsApplied() > 0L)
            .toList();
    if (tasks.isEmpty()) {
      return ImpactScope.empty();
    }
    String normalizedFactType = factType.trim().toUpperCase(Locale.ROOT);
    try {
      return switch (normalizedFactType) {
        case "ISSUE" -> resolveIssueScope(tasks);
        case "MERGE_REQUEST" -> resolveMergeRequestScope(tasks);
        case "INTEGRATION_TEST" -> resolveIntegrationTestScope(tasks);
        default -> ImpactScope.fallback();
      };
    } catch (DataAccessException e) {
      return ImpactScope.fallback();
    }
  }

  private SyncRun resolveMirrorRun(
      Long factRunId,
      Long expectedConfigId,
      String expectedSourceInstance) {
    SyncRun factRun = syncRunMapper.selectById(factRunId);
    if (factRun == null) {
      throw new IllegalStateException("事实刷新运行 " + factRunId + " 不存在");
    }
    if (factRun.getRunType() != SyncRunType.FACT_REFRESH) {
      throw new IllegalStateException("运行 " + factRunId + " 不是 FACT_REFRESH 事实子运行");
    }
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(expectedSourceInstance);
    if (!Objects.equals(factRun.getConfigId(), expectedConfigId)
        || !Objects.equals(
            GitlabSourceInstanceSupport.normalizeSourceInstance(factRun.getSourceInstance()),
            normalizedSource)) {
      throw new IllegalStateException("事实刷新运行与任务配置或数据源不一致: " + factRunId);
    }
    Long parentRunId = factRun.getParentRunId();
    if (parentRunId == null) {
      throw new IllegalStateException("事实刷新运行缺少镜像父运行: " + factRunId);
    }
    SyncRun mirrorRun = syncRunMapper.selectById(parentRunId);
    if (mirrorRun == null) {
      throw new IllegalStateException("镜像父运行 " + parentRunId + " 不存在");
    }
    if (!isMirrorRun(mirrorRun.getRunType())) {
      throw new IllegalStateException("事实刷新运行的父运行不是镜像运行: " + parentRunId);
    }
    if (mirrorRun.getStatus() != SyncRunStatus.SUCCESS
        && mirrorRun.getStatus() != SyncRunStatus.PARTIAL_SUCCESS) {
      throw new IllegalStateException("镜像父运行未形成可发布结果: " + parentRunId);
    }
    if (!Objects.equals(mirrorRun.getConfigId(), factRun.getConfigId())
        || !Objects.equals(
            GitlabSourceInstanceSupport.normalizeSourceInstance(mirrorRun.getSourceInstance()),
            normalizedSource)) {
      throw new IllegalStateException("镜像父运行与事实子运行的配置或数据源不一致: " + parentRunId);
    }
    return mirrorRun;
  }

  private boolean isMirrorRun(SyncRunType runType) {
    return runType == SyncRunType.FULL_SYNC
        || runType == SyncRunType.INCREMENTAL_SYNC
        || runType == SyncRunType.TABLE_REFRESH
        || runType == SyncRunType.SYSTEM_HOOK
        || runType == SyncRunType.COMPENSATION_SCAN
        || runType == SyncRunType.FULL_COMPENSATION_SCAN;
  }

  private boolean hasRecordedMirrorWork(SyncRun mirrorRun) {
    return positive(mirrorRun.getAppliedRows())
        || positive(mirrorRun.getPlannedTableCount())
        || positive(mirrorRun.getCompletedTableCount());
  }

  private boolean positive(Number value) {
    return value != null && value.longValue() > 0L;
  }

  private ImpactScope resolveIssueScope(List<SyncRunTableTask> tasks) {
    Set<Target> targets = new LinkedHashSet<>();
    for (SyncRunTableTask task : tasks) {
      String table = normalizeTable(task.getSourceTable());
      if (!hasValidAuthoritativeScope(task, table)) {
        return ImpactScope.fallback();
      }
      if (List.of("projects", "users", "labels", "milestones").contains(table)) {
        return ImpactScope.fallback();
      }
      switch (table) {
        case "issues" -> addIssueTargetsFromIssues(task, targets);
        case "notes" -> addIssueTargetsFromNotes(task, targets);
        case "label_links" -> addIssueTargetsFromLabelLinks(task, targets);
        case "issue_assignees" -> addIssueTargetsFromIssueAssignees(task, targets);
        case "issue_metrics" -> addIssueTargetsFromIssueMetrics(task, targets);
        case "resource_label_events" -> addIssueTargetsFromResourceLabelEvents(task, targets);
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

  private ImpactScope resolveMergeRequestScope(List<SyncRunTableTask> tasks) {
    Set<Target> targets = new LinkedHashSet<>();
    for (SyncRunTableTask task : tasks) {
      String table = normalizeTable(task.getSourceTable());
      if (!hasValidAuthoritativeScope(task, table)) {
        return ImpactScope.fallback();
      }
      if (List.of("projects", "users", "labels", "namespaces").contains(table)) {
        return ImpactScope.fallback();
      }
      switch (table) {
        case "merge_requests" -> addMergeRequestTargetsFromMergeRequests(task, targets);
        case "merge_request_metrics" -> addMergeRequestTargetsFromMergeRequestMetrics(task, targets);
        case "merge_request_reviewers", "merge_request_assignees" ->
            addMergeRequestTargetsFromJoinTable(task, table, targets);
        case "notes" -> addMergeRequestTargetsFromNotes(task, targets);
        case "label_links" -> addMergeRequestTargetsFromLabelLinks(task, targets);
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

  private ImpactScope resolveIntegrationTestScope(List<SyncRunTableTask> tasks) {
    ImpactScope issueScope = resolveIssueScope(tasks);
    if (issueScope.fallbackRequired() || !issueScope.targets().isEmpty()) {
      return issueScope;
    }
    boolean mayContainDeletedIntegrationSource =
        tasks.stream()
            .map(SyncRunTableTask::getSourceTable)
            .map(this::normalizeTable)
            .anyMatch(table -> List.of("issues", "notes", "label_links").contains(table));
    return mayContainDeletedIntegrationSource ? ImpactScope.fallback() : ImpactScope.empty();
  }

  private void addIssueTargetsFromIssues(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      addIssueTargetById(scopeLong(task, "id"), targets);
      return;
    }
    String issues = quoteMirrorTable("issues");
    targets.addAll(queryTargets(
        """
            select distinct project_id, iid
              from %s
             where mirror_task_id = ?
               and project_id is not null
               and iid is not null
            """.formatted(issues),
        task.getId()));
  }

  private void addIssueTargetsFromNotes(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      if ("Issue".equals(scopeText(task, "noteable_type"))) {
        addIssueTargetById(scopeLong(task, "noteable_id"), targets);
      }
      return;
    }
    String notes = quoteMirrorTable("notes");
    String issues = quoteMirrorTable("issues");
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

  private void addIssueTargetsFromLabelLinks(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      if ("Issue".equals(scopeText(task, "target_type"))) {
        addIssueTargetById(scopeLong(task, "target_id"), targets);
      }
      return;
    }
    String labelLinks = quoteMirrorTable("label_links");
    String issues = quoteMirrorTable("issues");
    targets.addAll(queryTargets(
        """
            select distinct i.project_id, i.iid
              from %s ll
              join %s i
                on i.id = ll.target_id
               and coalesce(i.mirror_deleted, false) = false
             where ll.mirror_task_id = ?
               and ll.target_type = 'Issue'
               and i.project_id is not null
               and i.iid is not null
            """.formatted(labelLinks, issues),
        task.getId()));
  }

  private void addIssueTargetsFromIssueAssignees(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      addIssueTargetById(scopeLong(task, "issue_id"), targets);
      return;
    }
    String assignees = quoteMirrorTable("issue_assignees");
    String issues = quoteMirrorTable("issues");
    targets.addAll(queryTargets(
        """
            select distinct i.project_id, i.iid
              from %s ia
              join %s i
                on i.id = ia.issue_id
               and coalesce(i.mirror_deleted, false) = false
             where ia.mirror_task_id = ?
               and i.project_id is not null
               and i.iid is not null
            """.formatted(assignees, issues),
        task.getId()));
  }

  private void addIssueTargetsFromIssueMetrics(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      addIssueTargetById(scopeLong(task, "issue_id"), targets);
    }
  }

  private void addIssueTargetsFromResourceLabelEvents(
      SyncRunTableTask task, Set<Target> targets) {
    String events = quoteMirrorTable("resource_label_events");
    String issues = quoteMirrorTable("issues");
    targets.addAll(queryTargets(
        """
            select distinct i.project_id, i.iid
              from %s event
              join %s i
                on i.id = event.issue_id
               and coalesce(i.mirror_deleted, false) = false
             where event.mirror_task_id = ?
               and coalesce(event.mirror_deleted, false) = false
               and event.issue_id is not null
               and i.project_id is not null
               and i.iid is not null
            """.formatted(events, issues),
        task.getId()));
  }

  private void addMergeRequestTargetsFromMergeRequests(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      addMergeRequestTargetById(scopeLong(task, "id"), targets);
      return;
    }
    String mergeRequests = quoteMirrorTable("merge_requests");
    targets.addAll(queryTargets(
        """
            select distinct target_project_id as project_id, iid
              from %s
             where mirror_task_id = ?
               and target_project_id is not null
               and iid is not null
            """.formatted(mergeRequests),
        task.getId()));
  }

  private void addMergeRequestTargetsFromMergeRequestMetrics(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      addMergeRequestTargetById(scopeLong(task, "merge_request_id"), targets);
      return;
    }
    String metrics = quoteMirrorTable("merge_request_metrics");
    String mergeRequests = quoteMirrorTable("merge_requests");
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
      SyncRunTableTask task, String sourceTable, Set<Target> targets) {
    if (isAuthoritative(task)) {
      addMergeRequestTargetById(scopeLong(task, "merge_request_id"), targets);
      return;
    }
    String joinTable = quoteMirrorTable(sourceTable);
    String mergeRequests = quoteMirrorTable("merge_requests");
    targets.addAll(queryTargets(
        """
            select distinct mr.target_project_id as project_id, mr.iid
              from %s jt
              join %s mr
                on mr.id = jt.merge_request_id
               and coalesce(mr.mirror_deleted, false) = false
             where jt.mirror_task_id = ?
               and mr.target_project_id is not null
               and mr.iid is not null
            """.formatted(joinTable, mergeRequests),
        task.getId()));
  }

  private void addMergeRequestTargetsFromNotes(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      if ("MergeRequest".equals(scopeText(task, "noteable_type"))) {
        addMergeRequestTargetById(scopeLong(task, "noteable_id"), targets);
      }
      return;
    }
    String notes = quoteMirrorTable("notes");
    String mergeRequests = quoteMirrorTable("merge_requests");
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

  private void addMergeRequestTargetsFromLabelLinks(SyncRunTableTask task, Set<Target> targets) {
    if (isAuthoritative(task)) {
      if ("MergeRequest".equals(scopeText(task, "target_type"))) {
        addMergeRequestTargetById(scopeLong(task, "target_id"), targets);
      }
      return;
    }
    String labelLinks = quoteMirrorTable("label_links");
    String mergeRequests = quoteMirrorTable("merge_requests");
    targets.addAll(queryTargets(
        """
            select distinct mr.target_project_id as project_id, mr.iid
              from %s ll
              join %s mr
                on mr.id = ll.target_id
               and coalesce(mr.mirror_deleted, false) = false
             where ll.mirror_task_id = ?
               and ll.target_type = 'MergeRequest'
               and mr.target_project_id is not null
               and mr.iid is not null
            """.formatted(labelLinks, mergeRequests),
        task.getId()));
  }

  private void addIssueTargetById(Long issueId, Set<Target> targets) {
    if (issueId == null) {
      return;
    }
    targets.addAll(queryTargets(
        """
            select distinct project_id, iid
              from %s
             where id = ?
               and project_id is not null
               and iid is not null
            """.formatted(quoteMirrorTable("issues")),
        issueId));
  }

  private void addMergeRequestTargetById(Long mergeRequestId, Set<Target> targets) {
    if (mergeRequestId == null) {
      return;
    }
    targets.addAll(queryTargets(
        """
            select distinct target_project_id as project_id, iid
              from %s
             where id = ?
               and target_project_id is not null
               and iid is not null
            """.formatted(quoteMirrorTable("merge_requests")),
        mergeRequestId));
  }

  private boolean hasValidAuthoritativeScope(SyncRunTableTask task, String table) {
    if (!isAuthoritative(task)) {
      return true;
    }
    Map<String, Object> scope = lookupScope(task);
    if (scope.isEmpty()) {
      return false;
    }
    Map<String, String> normalizedScope = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : scope.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        return false;
      }
      normalizedScope.put(entry.getKey(), String.valueOf(entry.getValue()));
    }
    return AuthoritativeRelationCatalog.isAuthoritativeTarget(table, normalizedScope);
  }

  private boolean isAuthoritative(SyncRunTableTask task) {
    return task != null && "AUTHORITATIVE".equalsIgnoreCase(task.getRowStrategy());
  }

  private Long scopeLong(SyncRunTableTask task, String column) {
    Object value = lookupScope(task).get(column);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    try {
      return Long.valueOf(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String scopeText(SyncRunTableTask task, String column) {
    Object value = lookupScope(task).get(column);
    return value == null ? "" : String.valueOf(value);
  }

  private Map<String, Object> lookupScope(SyncRunTableTask task) {
    if (task == null) {
      return Map.of();
    }
    try {
      return jsonUtils.toMap(task.getLookupScopeJson());
    } catch (IllegalStateException ignored) {
      return Map.of();
    }
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

  private String quoteMirrorTable(String sourceTable) {
    return quoteIdentifier(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable));
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
