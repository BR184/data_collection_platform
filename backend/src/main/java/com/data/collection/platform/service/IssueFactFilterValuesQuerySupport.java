package com.data.collection.platform.service;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 系统测试与客户问题记录/非法记录页的筛选候选值查询：持有两段 string_agg 候选值 SQL、
 * 行映射与聚合值拆分。候选值口径（成员拆分、占位值合并）与条件构建器的作用域谓词配套。
 */
@Component
class IssueFactFilterValuesQuerySupport {
  private static final String SYSTEM_TEST_VALUES_SQL = """
      with base as (
        select coalesce(project_name, '') as project_name,
               coalesce(module_names, '') as module_names,
               coalesce(testing_phase, '') as testing_phase,
               coalesce(illegal_reason, '') as illegal_reason,
               coalesce(illegal_reasons, '') as illegal_reasons,
               coalesce(author_name, '') as author_name,
               coalesce(assignee_name, '') as assignee_name,
               coalesce(issue_state, '') as issue_state,
               coalesce(severity_level, '') as severity_level,
               coalesce(bug_status, '') as bug_status,
               coalesce(category, '') as category,
               coalesce(milestone_title, '') as milestone_title
          from issue_fact
         where deleted = false
           and project_id = ?
           and is_illegal = true
           and is_excluded = false
           and (lower(coalesce(testing_phase, '')) like ? or lower(coalesce(testing_phase, '')) like ?)
      """
          + "     and "
          + "%s"
      + """
      )
      select
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(project_name), '') as value from base) t where value is not null) as project_names,
        (select string_agg(value, E'\n' order by value) from (
           select distinct nullif(btrim(module_name), '') as value
             from base
             cross join lateral regexp_split_to_table(coalesce(module_names, ''), ',') as modules(module_name)
           union
           select '未设定模块' where exists (select 1 from base where nullif(btrim(module_names), '') is null)
         ) t where value is not null) as module_names,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(testing_phase), '') as value from base) t where value is not null) as testing_phases,
        (select string_agg(value, E'\n' order by value) from (
           select distinct nullif(btrim(reason), '') as value
             from base
             cross join lateral regexp_split_to_table(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ',') as reasons(reason)
         ) t where value is not null) as illegal_reasons,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(author_name), '') as value from base) t where value is not null) as author_names,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(assignee_name), '') as value from base) t where value is not null) as assignee_names,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(issue_state), '') as value from base) t where value is not null) as issue_states,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(severity_level), '') as value from base) t where value is not null) as severity_levels,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(bug_status), '') as value from base) t where value is not null) as bug_statuses,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(category), '') as value from base) t where value is not null) as categories,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(milestone_title), '') as value from base) t where value is not null) as milestone_titles
      """;

  private static final String CUSTOMER_ISSUE_VALUES_SQL = """
      with base as (
        select coalesce(source_system, 'GITLAB') as source_system,
               coalesce(source_instance, 'default') as source_instance,
               project_id,
               issue_id,
               coalesce(project_name, '') as project_name,
               coalesce(module_names, '') as module_names,
               coalesce(function_name, '') as function_name,
               coalesce(testing_phase, '') as testing_phase,
               coalesce(reason_category, '') as reason_category,
               coalesce(severity_level, '') as severity_level,
               coalesce(priority_level, '') as priority_level,
               coalesce(issue_state, '') as issue_state,
               coalesce(bug_status, '') as bug_status,
               coalesce(category, '') as category,
               coalesce(author_name, '') as author_name,
               coalesce(handler_name, '') as handler_name,
               coalesce(assignee_name, '') as assignee_name,
               coalesce(fix_user, '') as fix_user,
               coalesce(delay_cause, '') as delay_cause,
               coalesce(planned_merge_version_branch, '') as planned_merge_version_branch,
               coalesce(milestone_title, '') as milestone_title,
               coalesce(illegal_reason, '') as illegal_reason,
               coalesce(illegal_reasons, '') as illegal_reasons
          from issue_fact
         where
      """
          + "%s"
      + """
      )
      select
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(project_name), '') as value from base) t where value is not null) as project_names,
        (select string_agg(value, E'\n' order by value) from (
           select distinct nullif(btrim(module_name), '') as value
             from base
             cross join lateral regexp_split_to_table(coalesce(module_names, ''), ',') as modules(module_name)
         ) t where value is not null) as module_names,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(function_name), '') as value from base) t where value is not null) as function_names,
        (select string_agg(value, E'\n' order by value) from (
           select distinct nullif(btrim(member.customer_name), '') as value
             from base
             join issue_fact_customer_members member
               on member.source_system = base.source_system
              and member.source_instance = base.source_instance
              and member.project_id = base.project_id
              and member.issue_id = base.issue_id
         ) t where value is not null) as customer_names,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(reason_category), '') as value from base) t where value is not null) as reason_categories,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(severity_level), '') as value from base) t where value is not null) as severity_levels,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(priority_level), '') as value from base) t where value is not null) as priority_levels,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(issue_state), '') as value from base) t where value is not null) as issue_states,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(bug_status), '') as value from base) t where value is not null) as bug_statuses,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(category), '') as value from base) t where value is not null) as categories,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(author_name), '') as value from base) t where value is not null) as author_names,
        (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(handler_name), '') as value from base) t where value is not null) as handler_names,
         (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(assignee_name), '') as value from base) t where value is not null) as assignee_names,
         (select string_agg(value, E'\n' order by value) from (
            select distinct nullif(btrim(testing_phase), '') as value from base
            union
            select '未设定测试阶段' where exists (
              select 1 from base where nullif(btrim(testing_phase), '') is null
            )
          ) t where value is not null) as testing_phases,
         (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(fix_user), '') as value from base) t where value is not null) as fix_users,
         (select string_agg(value, E'\n' order by value) from (
            select distinct nullif(btrim(delay_member.value), '') as value
              from base
              cross join lateral regexp_split_to_table(coalesce(delay_cause, ''), '[、，,&]') as delay_member(value)
         ) t where value is not null) as delay_causes,
         (select string_agg(value, E'\n' order by value) from (
            select distinct nullif(btrim(planned_merge_member.value), '') as value
              from base
              cross join lateral regexp_split_to_table(
                coalesce(planned_merge_version_branch, ''), '%s'
              ) as planned_merge_member(value)
         ) t where value is not null) as planned_merge_version_branches,
         (select string_agg(value, E'\n' order by value) from (select distinct nullif(btrim(milestone_title), '') as value from base) t where value is not null) as milestone_titles,
        (select string_agg(value, E'\n' order by value) from (
           select distinct nullif(btrim(reason), '') as value
             from base
             cross join lateral regexp_split_to_table(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ',') as reasons(reason)
         ) t where value is not null) as illegal_reasons
      """
          .formatted(CustomerIssuePlannedMergeBranchMembers.delimiterRegex());

  /** 系统测试非法记录候选值 SQL；predicate 为条件构建器生成的受支持原因片段。 */
  String systemTestIllegalValuesSql(String supportedReasonPredicate) {
    return SYSTEM_TEST_VALUES_SQL.formatted(supportedReasonPredicate);
  }

  /** 客户问题记录/非法记录候选值 SQL；predicate 为条件构建器生成的作用域谓词。 */
  String customerIssueValuesSql(String predicate) {
    return CUSTOMER_ISSUE_VALUES_SQL.formatted(predicate);
  }

  static List<String> splitAggregatedValues(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split("\\R"))
        .map(TextQuerySupport::trimToNull)
        .filter(item -> item != null)
        .distinct()
        .toList();
  }
}
