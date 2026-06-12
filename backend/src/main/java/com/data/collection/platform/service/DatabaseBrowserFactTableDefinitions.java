package com.data.collection.platform.service;

import static com.data.collection.platform.service.DatabaseBrowserDefinitionSupport.column;
import static com.data.collection.platform.service.DatabaseBrowserDefinitionSupport.definition;

import java.util.List;
import java.util.Map;

final class DatabaseBrowserFactTableDefinitions {

  private DatabaseBrowserFactTableDefinitions() {
  }

  static Map<String, DatabaseBrowserTableDefinition> definitions() {
    return Map.of(
        "issue_fact",
        definition(
            "议题事实表",
            List.of("source_system", "source_instance", "issue_iid", "title", "module_name", "severity_level", "priority_level"),
            "updated_at",
            column("id", "ID"),
            column("source_system", "来源系统"),
            column("source_instance", "来源实例"),
            column("ingest_channel", "接入通道"),
            column("project_id", "Project ID"),
            column("project_name", "项目名称"),
            column("issue_id", "Issue ID"),
            column("issue_iid", "Issue IID"),
            column("title", "标题"),
            column("issue_state", "状态"),
            column("issue_type", "类别"),
            column("milestone_title", "里程碑"),
            column("author_name", "提交人"),
            column("assignee_name", "处理人"),
            column("module_name", "模块名"),
            column("testing_phase", "测试阶段"),
            column("severity_level", "严重程度"),
            column("priority_level", "优先级"),
            column("urgency", "优先级(兼容字段)"),
            column("bug_status", "测试状态"),
            column("category", "缺陷原因"),
            column("system_test_label", "系统测试标签"),
            column("delay_issue", "是否延期"),
            column("delay_cause", "延期原因"),
            column("source_summary", "来源说明"),
            column("raw_payload", "原始载荷"),
            column("fact_refreshed_at", "事实刷新时间"),
            column("updated_at", "更新时间"),
            column("created_at", "创建时间")),
        "merge_request_fact",
        definition(
            "合并请求事实表",
            List.of("source_system", "source_instance", "merge_request_iid", "title", "module_name", "author_name"),
            "updated_at",
            column("id", "ID"),
            column("source_system", "来源系统"),
            column("source_instance", "来源实例"),
            column("ingest_channel", "接入通道"),
            column("project_id", "Project ID"),
            column("project_name", "项目名称"),
            column("repository_name", "仓库名称"),
            column("merge_request_id", "MR ID"),
            column("merge_request_iid", "MR IID"),
            column("title", "标题"),
            column("merge_request_state", "状态"),
            column("target_branch", "目标分支"),
            column("source_branch", "源分支"),
            column("author_name", "提交人"),
            column("merge_user_name", "合并人"),
            column("owner_name", "指派人"),
            column("reviewer_names", "评审人"),
            column("assignee_names", "处理人"),
            column("module_name", "模块名"),
            column("review_status", "走查状态"),
            column("review_duration_minutes", "走查时长(分钟)"),
            column("comment_rate", "代码注释率"),
            column("comment_rate_source", "注释率来源"),
            column("defect_count", "缺陷数"),
            column("defect_count_source", "缺陷数来源"),
            column("scan_status", "扫描状态"),
            column("scan_bug_count", "扫描问题数"),
            column("annotation_rate_result", "编码规范扫描结果"),
            column("bug_count_result", "静态扫描结果"),
            column("added_lines", "新增代码行数"),
            column("source_summary", "来源说明"),
            column("raw_payload", "原始载荷"),
            column("fact_refreshed_at", "事实刷新时间"),
            column("updated_at", "更新时间"),
            column("created_at", "创建时间")));
  }
}
