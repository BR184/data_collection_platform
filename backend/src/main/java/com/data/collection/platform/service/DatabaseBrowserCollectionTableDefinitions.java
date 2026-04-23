package com.data.collection.platform.service;

import static com.data.collection.platform.service.DatabaseBrowserDefinitionSupport.column;
import static com.data.collection.platform.service.DatabaseBrowserDefinitionSupport.definition;

import java.util.List;
import java.util.Map;

final class DatabaseBrowserCollectionTableDefinitions {

  private DatabaseBrowserCollectionTableDefinitions() {
  }

  static Map<String, DatabaseBrowserTableDefinition> definitions() {
    return Map.of(
        "collect_form_records",
        definition(
            "采集表单记录",
            List.of("template_code", "resource_type", "resource_id", "reviewer", "remark"),
            "updated_at",
            column("id", "ID"),
            column("gitlab_base_url", "GitLab 来源地址"),
            column("project_id", "Project ID"),
            column("request_iid", "请求 IID"),
            column("resource_type", "资源类型"),
            column("resource_id", "资源编号"),
            column("template_code", "模板编码"),
            column("form_title", "表单标题"),
            column("reviewer", "走查人"),
            column("review_duration_minutes", "走查时间(分钟)"),
            column("specification_score", "规范"),
            column("logic_score", "逻辑"),
            column("performance_score", "性能"),
            column("design_score", "设计"),
            column("other_score", "其他"),
            column("deleted", "是否作废"),
            column("updated_at", "更新时间"),
            column("created_at", "创建时间")),
        "code_review_external_metrics",
        definition(
            "代码走查外部指标导入表",
            List.of(
                "merge_request_iid",
                "comment_rate_source",
                "defect_count_source",
                "source_summary",
                "raw_payload"),
            "updated_at",
            column("id", "ID"),
            column("project_id", "Project ID"),
            column("merge_request_id", "MR ID"),
            column("merge_request_iid", "MR IID"),
            column("comment_rate", "代码注释率"),
            column("comment_rate_source", "注释率来源"),
            column("defect_count", "缺陷数"),
            column("defect_count_source", "缺陷来源"),
            column("source_summary", "来源说明"),
            column("raw_payload", "原始导入内容"),
            column("imported_at", "导入时间"),
            column("updated_at", "更新时间"),
            column("created_at", "创建时间")));
  }
}
