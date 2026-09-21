package com.data.collection.platform.service;

import static com.data.collection.platform.service.FactSourceRowValueSupport.defaultText;
import static com.data.collection.platform.service.FactSourceRowValueSupport.isClosed;
import static com.data.collection.platform.service.FactSourceRowValueSupport.nullableLong;
import static com.data.collection.platform.service.FactSourceRowValueSupport.readTextArray;
import static com.data.collection.platform.service.FactSourceRowValueSupport.toLocalDateTime;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarEntry;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarKey;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class IssueFactSourceRowMapper {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final String MIRROR_INGEST_CHANNEL = "MIRROR";

  IssueFact mapSource(
      ResultSet resultSet,
      String sourceInstance,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary,
      Map<String, String> customerNameAliases)
      throws SQLException {
    List<String> labels = readTextArray(resultSet.getArray("label_titles"));
    String title = defaultText(resultSet.getString("title"));
    String description = defaultText(resultSet.getString("description"));
    String notesText = defaultText(resultSet.getString("notes_text"), "");
    boolean closed = isClosed(resultSet);
    LocalDateTime createdAt = toLocalDateTime(resultSet.getTimestamp("created_at"));
    long projectId = resultSet.getLong("project_id");
    String projectName = resultSet.getString("project_name");
    boolean customerProject = CustomerIssueScopeRules.isCustomerProject(projectId, projectName);
    String testingPhase = IssueFactNormalizationRules.normalizeTestingPhase(labels);
    List<String> moduleNames =
        moduleDictionary.normalizeIssueModules(
            projectId, IssueFactNormalizationRules.normalizeModuleNames(labels));
    String severityLevel = IssueFactNormalizationRules.normalizeSeverityLevel(labels);
    String priorityLevel = IssueFactNormalizationRules.normalizePriorityLevel(labels);
    int resolveSlaDays = IssueFactNormalizationRules.resolveSlaDays(notesText);
    LocalDateTime resolveDeadlineAt =
        IssueFactNormalizationRules.resolveDeadline(createdAt, notesText);
    PhaseCalendarEntry phaseCalendar =
        calendar.get(
            new PhaseCalendarKey(
                projectId, IssuePhaseCalendarLoader.normalizeKey(testingPhase)));
    boolean customerIssue = isCustomerIssue(projectId, projectName, createdAt);
    boolean openCustomerIssue = customerIssue && !closed;
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    List<String> customerNames =
        customerProject
            ? IssueCustomerNameParser.parse(description, title, customerNameAliases)
            : List.of();
    IssueResponseTemplate responseTemplate =
        customerProject
            ? IssueResponseTemplateParser.parse(notesText)
            : IssueResponseTemplate.empty();

    IssueFact fact = new IssueFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(sourceInstance);
    fact.setIngestChannel(MIRROR_INGEST_CHANNEL);
    fact.setSourceSummary("GitLab issue 镜像聚合");
    fact.setRawPayload(notesText);
    fact.setProjectId(projectId);
    fact.setProjectName(defaultText(projectName));
    fact.setIssueId(resultSet.getLong("issue_id"));
    fact.setIssueIid(resultSet.getLong("issue_iid"));
    fact.setTitle(title);
    fact.setIssueState(closed ? "closed" : "opened");
    fact.setMilestoneTitle(defaultText(resultSet.getString("milestone_title")));
    fact.setAuthorName(defaultText(resultSet.getString("author_name")));
    fact.setHandlerName(defaultText(resultSet.getString("handler_name")));
    fact.setAssigneeName(defaultText(resultSet.getString("assignee_names")));
    fact.setFixUser(defaultText(resultSet.getString("fix_user")));
    fact.setCreatedAtSource(createdAt);
    fact.setUpdatedAtSource(toLocalDateTime(resultSet.getTimestamp("updated_at")));
    fact.setOdsUpdatedAt(toLocalDateTime(resultSet.getTimestamp("ods_updated_at")));
    fact.setClosedAtSource(toLocalDateTime(resultSet.getTimestamp("closed_at")));
    fact.setModuleName(moduleNames.isEmpty() ? null : moduleNames.getFirst());
    fact.setPrimaryModuleName(moduleNames.isEmpty() ? null : moduleNames.getFirst());
    fact.setModuleNames(String.join(", ", moduleNames));
    fact.setFunctionName(IssueFactNormalizationRules.normalizeFunctionName(title));
    fact.setCustomerNames(String.join(", ", customerNames));
    fact.setTestingPhase(testingPhase);
    fact.setSeverityLevel(severityLevel);
    fact.setSeverityAlias(IssueFactNormalizationRules.normalizeSeverityAlias(labels));
    fact.setPriorityLevel(priorityLevel);
    fact.setUrgency(priorityLevel);
    fact.setBugStatus(IssueFactNormalizationRules.normalizeBugStatus(labels));
    fact.setCategory(IssueFactNormalizationRules.normalizeCategory(labels));
    fact.setReasonCategory(IssueFactNormalizationRules.normalizeReasonCategory(notesText));
    fact.setSystemTestLabel(IssueFactNormalizationRules.normalizeSystemTestLabel(labels));
    fact.setLabelNames(String.join(", ", labels));
    fact.setExcluded(IssueFactNormalizationRules.isExcluded(labels, closed, fact.getProjectId()));
    fact.setExclusionReason(
        IssueFactNormalizationRules.exclusionReason(labels, closed, fact.getProjectId()));
    fact.setFixed(IssueFactNormalizationRules.isFixed(labels, closed));
    fact.setDelayIssue(IssueFactNormalizationRules.hasDelayFlag(labels, notesText));
    fact.setDelayReason(IssueFactNormalizationRules.normalizeDelayReason(labels, notesText));
    fact.setDelayCause(
        customerIssue
            ? IssueFactNormalizationRules.inferCustomerIssueDelayCause(labels, notesText)
            : IssueFactNormalizationRules.inferDelayCause(labels, notesText));
    fact.setRegression(IssueFactNormalizationRules.isRegression(labels, title));
    fact.setCrash(IssueFactNormalizationRules.isCrash(labels, title));
    fact.setLevel1Other(IssueFactNormalizationRules.isLevel1Other(labels, title));
    boolean fixedForIllegalCheck =
        StringUtils.hasText(fact.getBugStatus()) && fact.getBugStatus().contains("已修复");
    fact.setIllegal(
        customerIssue
            ? IssueFactNormalizationRules.isCustomerIssueIllegal(
                labels, moduleNames, notesText, fixedForIllegalCheck)
            : IssueFactNormalizationRules.isIllegal(
                labels, closed, moduleNames, notesText, fixedForIllegalCheck));
    fact.setIllegalReason(
        customerIssue
            ? IssueFactNormalizationRules.customerIssueIllegalReason(
                labels, moduleNames, notesText, fixedForIllegalCheck)
            : IssueFactNormalizationRules.illegalReason(
                labels, closed, moduleNames, notesText, fixedForIllegalCheck));
    fact.setIllegalReasons(
        String.join(
            ", ",
            customerIssue
                ? IssueFactNormalizationRules.customerIssueIllegalReasons(
                    labels, moduleNames, notesText, fixedForIllegalCheck)
                : IssueFactNormalizationRules.illegalReasons(
                    labels, closed, moduleNames, notesText, fixedForIllegalCheck)));
    fact.setHasResponse(IssueFactNormalizationRules.hasResponse(notesText));
    boolean responseDelayed =
        openCustomerIssue
            && IssueFactNormalizationRules.isResponseDelayed(
                labels, notesText, createdAt, priorityLevel, now);
    fact.setResearchTemplateTime(
        toLocalDateTime(resultSet.getTimestamp("research_template_time")));
    fact.setResponseOverdue(responseDelayed);
    fact.setResponseDelayed(responseDelayed);
    fact.setResolveSlaDays(resolveSlaDays);
    fact.setResolveDeadlineAt(resolveDeadlineAt);
    fact.setPlannedResolutionAt(responseTemplate.plannedResolutionAt());
    fact.setPlannedResolutionText(responseTemplate.plannedResolutionText());
    fact.setPlannedMergeVersionBranch(responseTemplate.plannedMergeVersionBranch());
    fact.setFixedLabelTime(
        Boolean.TRUE.equals(fact.getFixed())
                && StringUtils.hasText(fact.getBugStatus())
                && fact.getBugStatus().contains("已修复/完成")
            ? toLocalDateTime(resultSet.getTimestamp("fixed_label_time"))
            : null);
    fact.setResolveDelayed(
        openCustomerIssue
            && IssueFactNormalizationRules.isResolveDelayed(
                labels,
                Boolean.TRUE.equals(fact.getFixed()),
                IssueFactNormalizationRules.hasFixCaseNote(notesText),
                resolveDeadlineAt,
                now));
    fact.setLegacy(
        IssueFactNormalizationRules.isLegacy(
            labels,
            closed,
            createdAt,
            phaseCalendar == null ? null : phaseCalendar.phaseStartAt()));
    fact.setDeleted(false);
    return fact;
  }

  IssueFact mapOpenCustomerIssue(ResultSet resultSet, String sourceInstance)
      throws SQLException {
    IssueFact fact = new IssueFact();
    fact.setId(resultSet.getLong("id"));
    fact.setSourceSystem(
        defaultText(resultSet.getString("source_system"), DEFAULT_SOURCE_SYSTEM));
    fact.setSourceInstance(defaultText(resultSet.getString("source_instance"), sourceInstance));
    fact.setIngestChannel(defaultText(resultSet.getString("ingest_channel")));
    fact.setSourceSummary(defaultText(resultSet.getString("source_summary")));
    fact.setRawPayload(defaultText(resultSet.getString("raw_payload"), ""));
    fact.setProjectId(resultSet.getLong("project_id"));
    fact.setProjectName(defaultText(resultSet.getString("project_name")));
    fact.setIssueId(resultSet.getLong("issue_id"));
    fact.setIssueIid(nullableLong(resultSet, "issue_iid"));
    fact.setTitle(defaultText(resultSet.getString("title")));
    fact.setIssueState(defaultText(resultSet.getString("issue_state")));
    fact.setIssueType(defaultText(resultSet.getString("issue_type")));
    fact.setMilestoneTitle(defaultText(resultSet.getString("milestone_title")));
    fact.setAuthorName(defaultText(resultSet.getString("author_name")));
    fact.setHandlerName(defaultText(resultSet.getString("handler_name")));
    fact.setAssigneeName(defaultText(resultSet.getString("assignee_name")));
    fact.setCreatedAtSource(toLocalDateTime(resultSet.getTimestamp("created_at_source")));
    fact.setUpdatedAtSource(toLocalDateTime(resultSet.getTimestamp("updated_at_source")));
    fact.setOdsUpdatedAt(toLocalDateTime(resultSet.getTimestamp("ods_updated_at")));
    fact.setClosedAtSource(toLocalDateTime(resultSet.getTimestamp("closed_at_source")));
    fact.setModuleName(defaultText(resultSet.getString("module_name"), null));
    fact.setPrimaryModuleName(defaultText(resultSet.getString("primary_module_name"), null));
    fact.setModuleNames(defaultText(resultSet.getString("module_names")));
    fact.setCustomerNames(defaultText(resultSet.getString("customer_names")));
    fact.setFunctionName(defaultText(resultSet.getString("function_name")));
    fact.setTestingPhase(defaultText(resultSet.getString("testing_phase")));
    fact.setSeverityLevel(defaultText(resultSet.getString("severity_level")));
    fact.setSeverityAlias(defaultText(resultSet.getString("severity_alias")));
    fact.setPriorityLevel(defaultText(resultSet.getString("priority_level")));
    fact.setUrgency(defaultText(resultSet.getString("urgency")));
    fact.setBugStatus(defaultText(resultSet.getString("bug_status")));
    fact.setCategory(defaultText(resultSet.getString("category")));
    fact.setReasonCategory(defaultText(resultSet.getString("reason_category")));
    fact.setSystemTestLabel(defaultText(resultSet.getString("system_test_label")));
    fact.setLabelNames(defaultText(resultSet.getString("current_label_names")));
    fact.setExcluded(resultSet.getBoolean("is_excluded"));
    fact.setExclusionReason(defaultText(resultSet.getString("exclusion_reason")));
    fact.setFixed(resultSet.getBoolean("is_fixed"));
    fact.setDelayIssue(resultSet.getBoolean("delay_issue"));
    fact.setDelayReason(defaultText(resultSet.getString("delay_reason")));
    fact.setDelayCause(defaultText(resultSet.getString("delay_cause")));
    fact.setRegression(resultSet.getBoolean("is_regression"));
    fact.setCrash(resultSet.getBoolean("is_crash"));
    fact.setLevel1Other(resultSet.getBoolean("is_level1_other"));
    fact.setIllegal(resultSet.getBoolean("is_illegal"));
    fact.setIllegalReason(defaultText(resultSet.getString("illegal_reason")));
    fact.setIllegalReasons(defaultText(resultSet.getString("illegal_reasons")));
    fact.setHasResponse(resultSet.getBoolean("has_response"));
    fact.setResearchTemplateTime(
        toLocalDateTime(resultSet.getTimestamp("research_template_time")));
    fact.setResponseOverdue(resultSet.getBoolean("response_overdue"));
    fact.setResponseDelayed(resultSet.getBoolean("is_response_delayed"));
    fact.setResolveSlaDays(resultSet.getInt("resolve_sla_days"));
    fact.setResolveDeadlineAt(
        toLocalDateTime(resultSet.getTimestamp("resolve_deadline_at")));
    fact.setPlannedResolutionAt(
        toLocalDateTime(resultSet.getTimestamp("planned_resolution_at")));
    fact.setPlannedResolutionText(
        defaultText(resultSet.getString("planned_resolution_text")));
    fact.setPlannedMergeVersionBranch(
        defaultText(resultSet.getString("planned_merge_version_branch")));
    fact.setFixedLabelTime(toLocalDateTime(resultSet.getTimestamp("fixed_label_time")));
    fact.setResolveDelayed(resultSet.getBoolean("is_resolve_delayed"));
    fact.setLegacy(resultSet.getBoolean("is_legacy"));
    fact.setDeleted(false);
    return fact;
  }

  IssueFact mapSearchIndexFact(ResultSet resultSet, String sourceInstance)
      throws SQLException {
    IssueFact fact = new IssueFact();
    fact.setSourceSystem(
        defaultText(resultSet.getString("source_system"), DEFAULT_SOURCE_SYSTEM));
    fact.setSourceInstance(defaultText(resultSet.getString("source_instance"), sourceInstance));
    fact.setProjectId(resultSet.getLong("project_id"));
    fact.setIssueId(resultSet.getLong("issue_id"));
    fact.setIssueIid(nullableLong(resultSet, "issue_iid"));
    fact.setTitle(defaultText(resultSet.getString("title")));
    fact.setProjectName(defaultText(resultSet.getString("project_name")));
    fact.setModuleNames(defaultText(resultSet.getString("module_names")));
    fact.setCustomerNames(defaultText(resultSet.getString("customer_names")));
    fact.setTestingPhase(defaultText(resultSet.getString("testing_phase")));
    fact.setSystemTestLabel(defaultText(resultSet.getString("system_test_label")));
    fact.setLabelNames(defaultText(resultSet.getString("label_names")));
    fact.setReasonCategory(defaultText(resultSet.getString("reason_category")));
    fact.setIllegalReason(defaultText(resultSet.getString("illegal_reason")));
    fact.setAuthorName(defaultText(resultSet.getString("author_name")));
    fact.setHandlerName(defaultText(resultSet.getString("handler_name")));
    fact.setAssigneeName(defaultText(resultSet.getString("assignee_name")));
    fact.setBugStatus(defaultText(resultSet.getString("bug_status")));
    fact.setCategory(defaultText(resultSet.getString("category")));
    fact.setMilestoneTitle(defaultText(resultSet.getString("milestone_title")));
    return fact;
  }

  private boolean isCustomerIssue(
      Long projectId, String projectName, LocalDateTime createdAt) {
    return CustomerIssueScopeRules.isInCustomerIssueDateRange(createdAt)
        && CustomerIssueScopeRules.isCustomerProject(projectId, projectName);
  }
}
