package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** issue_fact 结果集到 {@link IssueFactRecord} 的唯一行映射；列名与仓库 SELECT 常量一一对应。 */
@Component
class IssueFactRecordRowMapper implements RowMapper<IssueFactRecord> {

  @Override
  public IssueFactRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new IssueFactRecord(
        rs.getLong("project_id"),
        IssueFactValueSupport.text(rs.getString("source_instance")),
        IssueFactValueSupport.text(rs.getString("project_name")),
        rs.getLong("issue_id"),
        rs.getInt("issue_iid"),
        IssueFactValueSupport.text(rs.getString("title")),
        IssueFactValueSupport.text(rs.getString("issue_state")),
        IssueFactValueSupport.text(rs.getString("testing_phase")),
        IssueFactValueSupport.text(rs.getString("system_test_label")),
        IssueFactValueSupport.text(rs.getString("severity_level")),
        IssueFactValueSupport.text(rs.getString("priority_level")),
        IssueFactValueSupport.text(rs.getString("bug_status")),
        IssueFactValueSupport.text(rs.getString("category")),
        IssueFactValueSupport.text(rs.getString("reason_category")),
        rs.getBoolean("is_excluded"),
        IssueFactValueSupport.text(rs.getString("exclusion_reason")),
        rs.getBoolean("is_fixed"),
        rs.getBoolean("is_regression"),
        rs.getBoolean("is_crash"),
        rs.getBoolean("is_level1_other"),
        rs.getBoolean("is_legacy"),
        IssueFactValueSupport.text(rs.getString("milestone_title")),
        IssueFactValueSupport.text(rs.getString("author_name")),
        IssueFactValueSupport.text(rs.getString("handler_name")),
        IssueFactValueSupport.text(rs.getString("assignee_name")),
        IssueFactValueSupport.text(rs.getString("fix_user")),
        IssueFactValueSupport.split(rs.getString("module_names")),
        IssueFactValueSupport.text(rs.getString("function_name")),
        IssueFactValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("delay_issue"),
        IssueFactValueSupport.text(rs.getString("delay_reason")),
        IssueFactValueSupport.text(rs.getString("delay_cause")),
        rs.getBoolean("is_response_delayed"),
        rs.getBoolean("is_resolve_delayed"),
        rs.getBoolean("is_illegal"),
        IssueFactValueSupport.text(rs.getString("illegal_reason")),
        IssueFactValueSupport.split(rs.getString("illegal_reasons")),
        IssueFactValueSupport.time(rs.getTimestamp("created_at_source")),
        IssueFactValueSupport.time(rs.getTimestamp("updated_at_source")),
        IssueFactValueSupport.time(rs.getTimestamp("closed_at_source")),
        IssueFactValueSupport.split(rs.getString("customer_names")),
        IssueFactValueSupport.time(rs.getTimestamp("planned_resolution_at")),
        IssueFactValueSupport.text(rs.getString("planned_resolution_text")),
        IssueFactValueSupport.text(rs.getString("planned_merge_version_branch")));
  }
}
