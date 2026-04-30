package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class FactBuildService {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final String DEFAULT_SOURCE_INSTANCE = "default";
  private static final String MIRROR_INGEST_CHANNEL = "MIRROR";
  private static final int FACT_BATCH_SIZE = 200;

  private static final String ISSUE_SOURCE_SQL = """
      with distinct_issue_labels as (
        select distinct
               ll.target_id as issue_id,
               nullif(btrim(l.title), '') as title
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'Issue'
           and l.title is not null
           and l.title <> ''
      ),
      issue_labels as (
        select issue_id,
               array_agg(title order by lower(title)) as label_titles
          from distinct_issue_labels
         group by issue_id
      ),
      issue_notes as (
        select n.noteable_id as issue_id,
               string_agg(coalesce(n.note, ''), E'\\n---\\n' order by coalesce(n.updated_at, n.created_at), n.id) as notes_text
          from ods_gitlab_notes n
         where coalesce(n.mirror_deleted, false) = false
           and n.noteable_type = 'Issue'
         group by n.noteable_id
      )
      select
        i.id as issue_id,
        i.iid as issue_iid,
        i.project_id,
        p.name as project_name,
        coalesce(milestone.title, '') as milestone_title,
        i.title,
        coalesce(author.name, '') as author_name,
        i.created_at,
        i.updated_at,
        coalesce(i.updated_at, i.created_at) as ods_updated_at,
        i.closed_at,
        i.state_id,
        labels.label_titles,
        coalesce(notes.notes_text, '') as notes_text
      from ods_gitlab_issues i
      left join ods_gitlab_projects p
        on p.id = i.project_id
       and coalesce(p.mirror_deleted, false) = false
      left join ods_gitlab_milestones milestone
        on milestone.id = i.milestone_id
       and coalesce(milestone.mirror_deleted, false) = false
      left join ods_gitlab_users author
        on author.id = i.author_id
       and coalesce(author.mirror_deleted, false) = false
      left join issue_labels labels
        on labels.issue_id = i.id
      left join issue_notes notes
        on notes.issue_id = i.id
      where coalesce(i.mirror_deleted, false) = false
      """;

  private static final String ISSUE_SOURCE_SQL_FALLBACK = """
      with distinct_issue_labels as (
        select distinct
               ll.target_id as issue_id,
               nullif(btrim(l.title), '') as title
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'Issue'
           and l.title is not null
           and l.title <> ''
      ),
      issue_labels as (
        select issue_id,
               array_agg(title order by lower(title)) as label_titles
          from distinct_issue_labels
         group by issue_id
      ),
      issue_notes as (
        select n.noteable_id as issue_id,
               string_agg(coalesce(n.note, ''), E'\\n---\\n' order by coalesce(n.updated_at, n.created_at), n.id) as notes_text
          from ods_gitlab_notes n
         where coalesce(n.mirror_deleted, false) = false
           and n.noteable_type = 'Issue'
         group by n.noteable_id
      )
      select
        i.id as issue_id,
        i.iid as issue_iid,
        i.project_id,
        p.name as project_name,
        '' as milestone_title,
        i.title,
        coalesce(author.name, '') as author_name,
        i.created_at,
        i.updated_at,
        coalesce(i.updated_at, i.created_at) as ods_updated_at,
        i.closed_at,
        i.state_id,
        labels.label_titles,
        coalesce(notes.notes_text, '') as notes_text
      from ods_gitlab_issues i
      left join ods_gitlab_projects p
        on p.id = i.project_id
       and coalesce(p.mirror_deleted, false) = false
      left join ods_gitlab_users author
        on author.id = i.author_id
       and coalesce(author.mirror_deleted, false) = false
      left join issue_labels labels
        on labels.issue_id = i.id
      left join issue_notes notes
        on notes.issue_id = i.id
      where coalesce(i.mirror_deleted, false) = false
      """;

  private static final String MERGE_REQUEST_SOURCE_SQL = """
      with reviewer_names as (
        select mr.merge_request_id,
               string_agg(distinct u.name, ', ' order by u.name) as reviewer_names
          from ods_gitlab_merge_request_reviewers mr
          join ods_gitlab_users u
            on u.id = mr.user_id
           and coalesce(u.mirror_deleted, false) = false
         where coalesce(mr.mirror_deleted, false) = false
         group by mr.merge_request_id
      ),
      assignee_names as (
        select ma.merge_request_id,
               string_agg(distinct u.name, ', ' order by u.name) as assignee_names
          from ods_gitlab_merge_request_assignees ma
          join ods_gitlab_users u
            on u.id = ma.user_id
           and coalesce(u.mirror_deleted, false) = false
         where coalesce(ma.mirror_deleted, false) = false
         group by ma.merge_request_id
      ),
      merge_request_labels as (
        select ll.target_id as merge_request_id,
               array_remove(
                 array_agg(
                   nullif(btrim(l.title), '')
                   order by coalesce(ll.source_updated_at, ll.updated_at, ll.created_at) desc nulls last, ll.id desc
                 ),
                 null
               ) as label_titles
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'MergeRequest'
         group by ll.target_id
      ),
      imported_metrics as (
        select m.project_id,
               m.merge_request_id,
               m.merge_request_iid,
               m.comment_rate,
               m.comment_rate_source,
               m.defect_count,
               m.defect_count_source,
               m.source_summary as metric_source_summary,
               m.raw_payload as metric_raw_payload
          from code_review_external_metrics m
      ),
      form_records as (
        select f.project_id,
               f.request_iid,
               max(nullif(btrim(f.reviewer), '')) as reviewer_name,
               max(f.review_duration_minutes) as review_duration_minutes
          from collect_form_records f
         where f.deleted = false
           and f.resource_type = 'merge_request'
         group by f.project_id, f.request_iid
      )
      select
        mr.id as merge_request_id,
        mr.iid as merge_request_iid,
        mr.target_project_id as project_id,
        mr.title,
        p.name as project_name,
        coalesce(owner_ns.path || '/' || p.path, p.path) as repository_name,
        coalesce(metrics.merged_at, mr.updated_at) as merged_at,
        mr.created_at,
        mr.updated_at,
        coalesce(mr.updated_at, mr.created_at) as ods_updated_at,
        coalesce(author.name, '') as author_name,
        coalesce(merge_user.name, '') as merge_user_name,
        coalesce(nullif(trim(reviewers.reviewer_names), ''), nullif(trim(assignees.assignee_names), ''), '') as owner_name,
        coalesce(reviewers.reviewer_names, '') as reviewer_names,
        coalesce(assignees.assignee_names, '') as assignee_names,
        coalesce(mr.target_branch, '') as target_branch,
        coalesce(mr.source_branch, '') as source_branch,
        coalesce((labels.label_titles)[1], '') as module_name,
        labels.label_titles as label_titles,
        metrics.added_lines as added_lines,
        forms.review_duration_minutes,
        imported_metrics.comment_rate,
        imported_metrics.comment_rate_source,
        imported_metrics.defect_count,
        imported_metrics.defect_count_source,
        imported_metrics.metric_source_summary,
        imported_metrics.metric_raw_payload
      from ods_gitlab_merge_requests mr
      left join ods_gitlab_merge_request_metrics metrics
        on metrics.merge_request_id = mr.id
       and coalesce(metrics.mirror_deleted, false) = false
      left join ods_gitlab_projects p
        on p.id = mr.target_project_id
       and coalesce(p.mirror_deleted, false) = false
      left join ods_gitlab_namespaces owner_ns
        on owner_ns.id = p.namespace_id
       and coalesce(owner_ns.mirror_deleted, false) = false
      left join ods_gitlab_users author
        on author.id = mr.author_id
       and coalesce(author.mirror_deleted, false) = false
      left join ods_gitlab_users merge_user
        on merge_user.id = mr.merge_user_id
       and coalesce(merge_user.mirror_deleted, false) = false
      left join reviewer_names reviewers
        on reviewers.merge_request_id = mr.id
      left join assignee_names assignees
        on assignees.merge_request_id = mr.id
      left join merge_request_labels labels
        on labels.merge_request_id = mr.id
      left join imported_metrics
        on imported_metrics.project_id = mr.target_project_id
       and (imported_metrics.merge_request_id = mr.id or imported_metrics.merge_request_iid = mr.iid)
      left join form_records forms
        on forms.project_id = mr.target_project_id
       and forms.request_iid = mr.iid
      where coalesce(mr.mirror_deleted, false) = false
      """;

  private final JdbcTemplate jdbcTemplate;
  private final IssueFactMapper issueFactMapper;
  private final MergeRequestFactMapper mergeRequestFactMapper;
  private final ModuleDictionaryService moduleDictionaryService;

  public FactBuildService(
      JdbcTemplate jdbcTemplate,
      IssueFactMapper issueFactMapper,
      MergeRequestFactMapper mergeRequestFactMapper,
      ModuleDictionaryService moduleDictionaryService) {
    this.jdbcTemplate = jdbcTemplate;
    this.issueFactMapper = issueFactMapper;
    this.mergeRequestFactMapper = mergeRequestFactMapper;
    this.moduleDictionaryService = moduleDictionaryService;
  }

  public FactBuildResponse rebuildAllFacts(boolean full) {
    FactBuildResponse issue = rebuildIssueFacts(full);
    FactBuildResponse mergeRequest = rebuildMergeRequestFacts(full);
    return new FactBuildResponse(
        "all",
        full,
        issue.affectedRows() + mergeRequest.affectedRows(),
        "事实表构建完成：议题 " + issue.affectedRows() + " 条，合并请求 " + mergeRequest.affectedRows() + " 条");
  }

  public FactBuildResponse rebuildIssueFacts(boolean full) {
    LocalDateTime changedSince = full ? null : getIssueFactChangedSince();
    try {
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar = loadPhaseCalendar();
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      List<IssueFact> facts = loadIssueFacts(changedSince, calendar, moduleDictionary);
      batchUpsertIssueFacts(facts);
      return new FactBuildResponse(
          "issue",
          full,
          facts.size(),
          changedSince == null ? "议题事实已全量构建" : "议题事实已按增量构建");
    } catch (DataAccessException e) {
      log.warn("Failed to rebuild issue facts", e);
      throw e;
    }
  }

  private List<IssueFact> loadIssueFacts(
      LocalDateTime changedSince,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary) {
    try {
      return queryIssueFacts(ISSUE_SOURCE_SQL, changedSince, calendar, moduleDictionary);
    } catch (DataAccessException error) {
      if (!isMilestoneQueryFallbackAllowed(error)) {
        throw error;
      }
      log.warn("Issue fact build fallback activated because milestone join is unavailable", error);
      return queryIssueFacts(ISSUE_SOURCE_SQL_FALLBACK, changedSince, calendar, moduleDictionary);
    }
  }

  private List<IssueFact> queryIssueFacts(
      String baseSql,
      LocalDateTime changedSince,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary) {
    String sql = baseSql + (changedSince == null ? "" : " and coalesce(i.updated_at, i.created_at) > ?");
    return changedSince == null
        ? jdbcTemplate.query(sql, (rs, rowNum) -> mapIssueFact(rs, calendar, moduleDictionary))
        : jdbcTemplate.query(sql, (rs, rowNum) -> mapIssueFact(rs, calendar, moduleDictionary), Timestamp.valueOf(changedSince));
  }

  private boolean isMilestoneQueryFallbackAllowed(DataAccessException error) {
    String message =
        error.getMostSpecificCause() == null
            ? error.getMessage()
            : error.getMostSpecificCause().getMessage();
    if (!StringUtils.hasText(message)) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return normalized.contains("ods_gitlab_milestones")
        || normalized.contains("milestone_id")
        || normalized.contains("milestone");
  }

  public FactBuildResponse rebuildMergeRequestFacts(boolean full) {
    LocalDateTime changedSince = full ? null : getMergeRequestFactChangedSince();
    String sql = MERGE_REQUEST_SOURCE_SQL + (changedSince == null ? "" : " and coalesce(mr.updated_at, mr.created_at) > ?");
    try {
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      List<MergeRequestFact> facts =
          changedSince == null
              ? jdbcTemplate.query(sql, (rs, rowNum) -> mapMergeRequestFact(rs, rowNum, moduleDictionary))
              : jdbcTemplate.query(sql, (rs, rowNum) -> mapMergeRequestFact(rs, rowNum, moduleDictionary), Timestamp.valueOf(changedSince));
      batchUpsertMergeRequestFacts(facts);
      return new FactBuildResponse(
          "merge-request",
          full,
          facts.size(),
          changedSince == null ? "合并请求事实已全量构建" : "合并请求事实已按增量构建");
    } catch (DataAccessException e) {
      log.warn("Failed to rebuild merge request facts", e);
      throw e;
    }
  }

  private LocalDateTime getIssueFactChangedSince() {
    if (hasIssueFactsMissingSearchIndexes()) {
      log.info("Issue fact search indexes are missing on existing rows; next rebuild will refresh all issue facts");
      return null;
    }
    return jdbcTemplate.queryForObject(
        """
            select max(ods_updated_at)
              from issue_fact
             where source_system = ?
               and source_instance = ?
            """,
        LocalDateTime.class,
            DEFAULT_SOURCE_SYSTEM,
            DEFAULT_SOURCE_INSTANCE);
  }

  private LocalDateTime getMergeRequestFactChangedSince() {
    if (hasMergeRequestFactsMissingSearchIndexes()) {
      log.info(
          "Merge request fact search indexes are missing on existing rows; next rebuild will refresh all merge request facts");
      return null;
    }
    return jdbcTemplate.queryForObject(
        """
            select max(ods_updated_at)
              from merge_request_fact
             where source_system = ?
               and source_instance = ?
            """,
        LocalDateTime.class,
            DEFAULT_SOURCE_SYSTEM,
            DEFAULT_SOURCE_INSTANCE);
  }

  private boolean hasIssueFactsMissingSearchIndexes() {
    Boolean result =
        jdbcTemplate.queryForObject(
            """
            select exists (
                select 1
                  from issue_fact
                 where source_system = ?
                   and source_instance = ?
                   and deleted = false
                   and (
                        search_text is null
                     or search_compact is null
                     or search_spell is null
                     or search_initials is null
                     or primary_phase_label is null
                     or phase_filter_value is null
                   )
                 limit 1
            )
            """,
            Boolean.class,
            DEFAULT_SOURCE_SYSTEM,
            DEFAULT_SOURCE_INSTANCE);
    return Boolean.TRUE.equals(result);
  }

  private boolean hasMergeRequestFactsMissingSearchIndexes() {
    Boolean result =
        jdbcTemplate.queryForObject(
            """
            select exists (
                select 1
                  from merge_request_fact
                 where source_system = ?
                   and source_instance = ?
                   and deleted = false
                   and (
                        search_text is null
                     or search_compact is null
                     or search_spell is null
                     or search_initials is null
                     or owner_search_text is null
                     or owner_search_compact is null
                     or owner_search_spell is null
                     or owner_search_initials is null
                   )
                 limit 1
            )
            """,
            Boolean.class,
            DEFAULT_SOURCE_SYSTEM,
            DEFAULT_SOURCE_INSTANCE);
    return Boolean.TRUE.equals(result);
  }

  private Map<PhaseCalendarKey, PhaseCalendarEntry> loadPhaseCalendar() {
    List<PhaseCalendarEntry> entries = jdbcTemplate.query(
        """
            select project_id, testing_phase, phase_start_at, phase_end_at, enabled
              from testing_phase_calendar
             where enabled = true
            """,
        (rs, rowNum) -> new PhaseCalendarEntry(
            rs.getLong("project_id"),
            defaultText(rs.getString("testing_phase"), null),
            toLocalDateTime(rs.getTimestamp("phase_start_at")),
            toLocalDateTime(rs.getTimestamp("phase_end_at")),
            rs.getBoolean("enabled")));
    Map<PhaseCalendarKey, PhaseCalendarEntry> result = new LinkedHashMap<>();
    entries.stream()
        .sorted(Comparator.comparing(PhaseCalendarEntry::phaseStartAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
        .forEach(entry -> result.putIfAbsent(new PhaseCalendarKey(entry.projectId(), normalizeKey(entry.testingPhase())), entry));
    return result;
  }

  private IssueFact mapIssueFact(
      ResultSet rs,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary) throws SQLException {
    List<String> labels = readTextArray(rs.getArray("label_titles"));
    String title = defaultText(rs.getString("title"));
    String notesText = defaultText(rs.getString("notes_text"), "");
    boolean closed = isClosed(rs);
    LocalDateTime createdAt = toLocalDateTime(rs.getTimestamp("created_at"));
    String testingPhase = IssueFactNormalizationRules.normalizeTestingPhase(labels);
    List<String> moduleNames =
        moduleDictionary.normalizeIssueModules(
            rs.getLong("project_id"),
            IssueFactNormalizationRules.normalizeModuleNames(labels));
    String severityLevel = IssueFactNormalizationRules.normalizeSeverityLevel(labels);
    String priorityLevel = IssueFactNormalizationRules.normalizePriorityLevel(labels);
    int resolveSlaDays = IssueFactNormalizationRules.resolveSlaDays(notesText);
    LocalDateTime resolveDeadlineAt = IssueFactNormalizationRules.resolveDeadline(createdAt, resolveSlaDays);
    PhaseCalendarEntry phaseCalendar = calendar.get(new PhaseCalendarKey(rs.getLong("project_id"), normalizeKey(testingPhase)));

    IssueFact fact = new IssueFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(DEFAULT_SOURCE_INSTANCE);
    fact.setIngestChannel(MIRROR_INGEST_CHANNEL);
    fact.setSourceSummary("GitLab issue 镜像聚合");
    fact.setRawPayload(notesText);
    fact.setProjectId(rs.getLong("project_id"));
    fact.setProjectName(defaultText(rs.getString("project_name")));
    fact.setIssueId(rs.getLong("issue_id"));
    fact.setIssueIid(rs.getLong("issue_iid"));
    fact.setTitle(title);
    fact.setIssueState(closed ? "closed" : "opened");
    fact.setMilestoneTitle(defaultText(rs.getString("milestone_title")));
    fact.setAuthorName(defaultText(rs.getString("author_name")));
    fact.setCreatedAtSource(createdAt);
    fact.setUpdatedAtSource(toLocalDateTime(rs.getTimestamp("updated_at")));
    fact.setOdsUpdatedAt(toLocalDateTime(rs.getTimestamp("ods_updated_at")));
    fact.setClosedAtSource(toLocalDateTime(rs.getTimestamp("closed_at")));
    fact.setModuleName(moduleNames.isEmpty() ? null : moduleNames.get(0));
    fact.setPrimaryModuleName(moduleNames.isEmpty() ? null : moduleNames.get(0));
    fact.setModuleNames(String.join(", ", moduleNames));
    fact.setFunctionName(IssueFactNormalizationRules.normalizeFunctionName(title));
    fact.setTestingPhase(testingPhase);
    fact.setSeverityLevel(severityLevel);
    fact.setSeverityAlias(IssueFactNormalizationRules.normalizeSeverityAlias(labels));
    fact.setPriorityLevel(priorityLevel);
    fact.setUrgency(priorityLevel);
    fact.setBugStatus(closed ? "已关闭" : "未关闭");
    fact.setCategory(
        IssueFactNormalizationRules.isRegression(labels, title) ? "回退"
            : IssueFactNormalizationRules.isCrash(labels, title) ? "挂机"
            : IssueFactNormalizationRules.isLevel1Other(labels, title) ? "其他一级"
            : null);
    fact.setReasonCategory(IssueFactNormalizationRules.normalizeReasonCategory(labels, notesText));
    fact.setSystemTestLabel(IssueFactNormalizationRules.normalizeSystemTestLabel(labels));
    fact.setLabelNames(String.join(", ", labels));
    fact.setExcluded(IssueFactNormalizationRules.isExcluded(labels, closed));
    fact.setExclusionReason(IssueFactNormalizationRules.exclusionReason(labels, closed));
    fact.setFixed(IssueFactNormalizationRules.isFixed(labels, closed));
    fact.setDelayIssue(IssueFactNormalizationRules.hasDelayFlag(labels, notesText));
    fact.setDelayReason(IssueFactNormalizationRules.normalizeDelayReason(labels, notesText));
    fact.setDelayCause(IssueFactNormalizationRules.inferDelayCause(labels, notesText));
    fact.setRegression(IssueFactNormalizationRules.isRegression(labels, title));
    fact.setCrash(IssueFactNormalizationRules.isCrash(labels, title));
    fact.setLevel1Other(IssueFactNormalizationRules.isLevel1Other(labels, title));
    fact.setIllegal(IssueFactNormalizationRules.isIllegal(labels, closed, moduleNames, notesText, Boolean.TRUE.equals(fact.getFixed())));
    fact.setIllegalReason(IssueFactNormalizationRules.illegalReason(labels, closed, moduleNames, notesText, Boolean.TRUE.equals(fact.getFixed())));
    fact.setHasResponse(IssueFactNormalizationRules.hasResponse(notesText));
    boolean responseDelayed = IssueFactNormalizationRules.isResponseDelayed(labels, notesText);
    fact.setResponseOverdue(responseDelayed);
    fact.setResponseDelayed(responseDelayed);
    fact.setResolveSlaDays(resolveSlaDays);
    fact.setResolveDeadlineAt(resolveDeadlineAt);
    fact.setResolveDelayed(IssueFactNormalizationRules.isResolveDelayed(
        labels,
        Boolean.TRUE.equals(fact.getFixed()),
        resolveDeadlineAt,
        LocalDateTime.now()));
    fact.setLegacy(IssueFactNormalizationRules.isLegacy(
        closed,
        createdAt,
        phaseCalendar == null ? null : phaseCalendar.phaseStartAt()));
    fact.setDeleted(false);
    return fact;
  }

  private MergeRequestFact mapMergeRequestFact(
      ResultSet rs, int rowNum, ModuleDictionary moduleDictionary) throws SQLException {
    List<String> labels = readTextArray(rs.getArray("label_titles"));
    MergeRequestFact fact = new MergeRequestFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(DEFAULT_SOURCE_INSTANCE);
    fact.setIngestChannel(MIRROR_INGEST_CHANNEL);
    fact.setSourceSummary(defaultText(rs.getString("metric_source_summary"), "GitLab merge request 镜像聚合"));
    fact.setRawPayload(defaultText(rs.getString("metric_raw_payload"), null));
    fact.setProjectId(rs.getLong("project_id"));
    fact.setProjectName(defaultText(rs.getString("project_name")));
    fact.setRepositoryName(defaultText(rs.getString("repository_name")));
    fact.setMergeRequestId(rs.getLong("merge_request_id"));
    fact.setMergeRequestIid(rs.getLong("merge_request_iid"));
    fact.setTitle(defaultText(rs.getString("title")));
    fact.setMergeRequestState(toLocalDateTime(rs.getTimestamp("merged_at")) == null ? "opened" : "merged");
    fact.setTargetBranch(defaultText(rs.getString("target_branch")));
    fact.setSourceBranch(defaultText(rs.getString("source_branch")));
    fact.setAuthorName(defaultText(rs.getString("author_name")));
    fact.setMergeUserName(defaultText(rs.getString("merge_user_name")));
    fact.setOwnerName(defaultText(rs.getString("owner_name")));
    fact.setReviewerNames(defaultText(rs.getString("reviewer_names")));
    fact.setAssigneeNames(defaultText(rs.getString("assignee_names")));
    fact.setModuleName(
        moduleDictionary.normalizeMergeRequestModule(
            rs.getLong("project_id"),
            defaultText(rs.getString("module_name"))));
    fact.setLabelNames(String.join(", ", labels));
    fact.setCreatedAtSource(toLocalDateTime(rs.getTimestamp("created_at")));
    fact.setUpdatedAtSource(toLocalDateTime(rs.getTimestamp("updated_at")));
    fact.setOdsUpdatedAt(toLocalDateTime(rs.getTimestamp("ods_updated_at")));
    fact.setMergedAtSource(toLocalDateTime(rs.getTimestamp("merged_at")));
    fact.setReviewStatus(rs.getObject("review_duration_minutes") == null ? "PENDING" : "COMPLETED");
    fact.setReviewDurationMinutes((Integer) rs.getObject("review_duration_minutes"));
    fact.setCommentRate((BigDecimal) rs.getObject("comment_rate"));
    fact.setCommentRateSource(defaultText(rs.getString("comment_rate_source")));
    fact.setDefectCount((Integer) rs.getObject("defect_count"));
    fact.setDefectCountSource(defaultText(rs.getString("defect_count_source")));
    fact.setScanStatus(null);
    fact.setScanBugCount(null);
    fact.setAddedLines((Integer) rs.getObject("added_lines"));
    fact.setDeleted(false);
    return fact;
  }

  private void batchUpsertIssueFacts(List<IssueFact> facts) {
    for (List<IssueFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      issueFactMapper.batchUpsert(batch);
      refreshIssueFactSearchIndexes(batch);
    }
  }

  private void batchUpsertMergeRequestFacts(List<MergeRequestFact> facts) {
    for (List<MergeRequestFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      mergeRequestFactMapper.batchUpsert(batch);
      refreshMergeRequestFactSearchIndexes(batch);
    }
  }

  private void refreshIssueFactSearchIndexes(List<IssueFact> facts) {
    jdbcTemplate.batchUpdate(
        """
        update issue_fact
           set search_text = ?,
               search_compact = ?,
               search_spell = ?,
               search_initials = ?,
               title_search_text = ?,
               title_search_compact = ?,
               title_search_spell = ?,
               title_search_initials = ?,
               module_search_text = ?,
               module_search_compact = ?,
               module_search_spell = ?,
               module_search_initials = ?,
               milestone_search_text = ?,
               milestone_search_compact = ?,
               milestone_search_spell = ?,
               milestone_search_initials = ?,
               author_search_text = ?,
               author_search_compact = ?,
               author_search_spell = ?,
               author_search_initials = ?,
               assignee_search_text = ?,
               assignee_search_compact = ?,
               assignee_search_spell = ?,
               assignee_search_initials = ?,
               primary_phase_label = ?,
               phase_filter_value = ?,
               phase_search_text = ?,
               phase_search_compact = ?,
               phase_search_spell = ?,
               phase_search_initials = ?
         where source_system = ?
           and source_instance = ?
           and project_id = ?
           and issue_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        (statement, fact) -> bindIssueSearchIndex(statement, fact));
  }

  private void bindIssueSearchIndex(PreparedStatement statement, IssueFact fact) throws SQLException {
    FactSearchIndexSupport.IssueSearchIndexes indexes = FactSearchIndexSupport.buildIssueIndexes(fact);
    int index = 1;
    index = bindSearchIndex(statement, index, indexes.keyword());
    index = bindSearchIndex(statement, index, indexes.title());
    index = bindSearchIndex(statement, index, indexes.module());
    index = bindSearchIndex(statement, index, indexes.milestone());
    index = bindSearchIndex(statement, index, indexes.author());
    index = bindSearchIndex(statement, index, indexes.assignee());
    statement.setString(index++, indexes.primaryPhaseLabel());
    statement.setString(index++, indexes.phaseFilterValue());
    index = bindSearchIndex(statement, index, indexes.phase());
    statement.setString(index++, fact.getSourceSystem());
    statement.setString(index++, fact.getSourceInstance());
    statement.setLong(index++, fact.getProjectId());
    statement.setLong(index, fact.getIssueId());
  }

  private void refreshMergeRequestFactSearchIndexes(List<MergeRequestFact> facts) {
    jdbcTemplate.batchUpdate(
        """
        update merge_request_fact
           set search_text = ?,
               search_compact = ?,
               search_spell = ?,
               search_initials = ?,
               owner_search_text = ?,
               owner_search_compact = ?,
               owner_search_spell = ?,
               owner_search_initials = ?
         where source_system = ?
           and source_instance = ?
           and project_id = ?
           and merge_request_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        (statement, fact) -> bindMergeRequestSearchIndex(statement, fact));
  }

  private void bindMergeRequestSearchIndex(PreparedStatement statement, MergeRequestFact fact)
      throws SQLException {
    FactSearchIndexSupport.MergeRequestSearchIndexes indexes =
        FactSearchIndexSupport.buildMergeRequestIndexes(fact);
    int index = 1;
    index = bindSearchIndex(statement, index, indexes.keyword());
    index = bindSearchIndex(statement, index, indexes.owner());
    statement.setString(index++, fact.getSourceSystem());
    statement.setString(index++, fact.getSourceInstance());
    statement.setLong(index++, fact.getProjectId());
    statement.setLong(index, fact.getMergeRequestId());
  }

  private int bindSearchIndex(
      PreparedStatement statement, int parameterIndex, TextQuerySupport.SearchIndex index)
      throws SQLException {
    statement.setString(parameterIndex++, index.normalized());
    statement.setString(parameterIndex++, index.compact());
    statement.setString(parameterIndex++, index.spell());
    statement.setString(parameterIndex++, index.initials());
    return parameterIndex;
  }

  private <T> List<List<T>> partition(List<T> items, int batchSize) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }
    List<List<T>> result = new ArrayList<>();
    for (int start = 0; start < items.size(); start += batchSize) {
      int end = Math.min(start + batchSize, items.size());
      result.add(items.subList(start, end));
    }
    return result;
  }

  private boolean isClosed(ResultSet rs) throws SQLException {
    Timestamp closedAt = rs.getTimestamp("closed_at");
    Integer stateId = (Integer) rs.getObject("state_id");
    return closedAt != null || (stateId != null && stateId != 1);
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private List<String> readTextArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (!(raw instanceof Object[] values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object value : values) {
      String normalized = defaultText(value == null ? null : String.valueOf(value), null);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return result;
  }

  private String normalizeKey(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
  }

  private String defaultText(String value) {
    return defaultText(value, "");
  }

  private String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private record PhaseCalendarKey(Long projectId, String testingPhase) {
  }

  private record PhaseCalendarEntry(
      Long projectId,
      String testingPhase,
      LocalDateTime phaseStartAt,
      LocalDateTime phaseEndAt,
      boolean enabled) {
  }
}
