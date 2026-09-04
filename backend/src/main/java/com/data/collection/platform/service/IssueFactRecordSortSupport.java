package com.data.collection.platform.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** issue_fact 记录查询的排序字段白名单与 SQL 排序片段，防止动态列名注入。 */
final class IssueFactRecordSortSupport {
  private static final Map<String, String> SORT_COLUMNS = createSortColumns();

  private IssueFactRecordSortSupport() {}

  /** 把前端排序字段解析为白名单 SQL 列表达式；未知或缺失字段回退到 updated_at_source。 */
  static String sortColumn(String sortField) {
    // sortField 为 null 是合法输入（导出请求可不带排序字段）；不可变映射对 null 键查询会抛 NPE。
    return sortField == null
        ? "updated_at_source"
        : SORT_COLUMNS.getOrDefault(sortField, "updated_at_source");
  }

  /** 只允许 asc/desc 两个方向；空或未知值回退 desc。 */
  static String sortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }

  /** 与方向配套的 NULL 排列：升序 null 在后、降序 null 在前。 */
  static String nullsClause(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? " nulls last" : " nulls first";
  }

  private static Map<String, String> createSortColumns() {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("issueIid", "issue_iid");
    columns.put("title", "lower(coalesce(title, ''))");
    columns.put("projectName", "lower(coalesce(project_name, ''))");
    columns.put("moduleNames", "lower(coalesce(module_names, ''))");
    columns.put("functionName", "lower(coalesce(function_name, ''))");
    columns.put("customerNames", "lower(coalesce(customer_names, ''))");
    columns.put("testingPhase", "lower(coalesce(testing_phase, ''))");
    columns.put("fixUser", "lower(coalesce(fix_user, ''))");
    columns.put("delayCause", "lower(coalesce(delay_cause, ''))");
    columns.put("reasonCategory", "lower(coalesce(reason_category, ''))");
    columns.put("illegalReason", "lower(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''))");
    columns.put("severityLevel", "lower(coalesce(severity_level, ''))");
    columns.put("priorityLevel", "lower(coalesce(priority_level, ''))");
    columns.put("bugStatus", "lower(coalesce(bug_status, ''))");
    columns.put("issueState", "lower(coalesce(issue_state, ''))");
    columns.put("authorName", "lower(coalesce(author_name, ''))");
    columns.put("handlerName", "lower(coalesce(handler_name, ''))");
    columns.put("assigneeName", "lower(coalesce(assignee_name, ''))");
    columns.put("category", "lower(coalesce(category, ''))");
    columns.put("milestoneTitle", "lower(coalesce(milestone_title, ''))");
    columns.put("plannedResolutionAt", "planned_resolution_at");
    columns.put("plannedMergeVersionBranch", "lower(coalesce(planned_merge_version_branch, ''))");
    columns.put("createdAt", "created_at_source");
    columns.put("updatedAt", "updated_at_source");
    columns.put("closedAt", "closed_at_source");
    return Map.copyOf(columns);
  }
}
