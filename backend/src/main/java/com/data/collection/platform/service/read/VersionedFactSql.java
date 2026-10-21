package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.FactReadQueryContext;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.PublicationFactTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 把既有业务 SELECT 的事实输入显式限定到读取组合，复用原公式、筛选与排序，不修改连接或数据库状态。 */
public final class VersionedFactSql {
  private VersionedFactSql() {}

  /**
   * 用可见性 CTE 限定三类事实及其父修订关系；参数位于原业务参数之前。
   *
   * @param selection 服务端核验的固定版本组合
   * @param querySql 服务端业务只读 SQL；支持原有 WITH，不接受递归
   * @param businessArguments 原 SQL 参数
   * @return 可直接交给 JdbcTemplate 的 SQL 与完整绑定参数
   */
  public static Query bind(FactReadSelection selection, String querySql, List<?> businessArguments) {
    String sql = querySql.stripLeading();
    String lower = sql.toLowerCase(Locale.ROOT);
    if (java.util.regex.Pattern.compile("\"?[a-z_][a-z0-9_]*\"?\\s*\\.\\s*\"?(issue_fact|merge_request_fact|integration_test_fact|issue_fact_customer_members|merge_request_commit_fact)\\b")
        .matcher(lower).find()) {
      throw new IllegalArgumentException("事实查询不能以模式限定名绕过版本输入");
    }
    if ((!lower.startsWith("select ") && !lower.startsWith("select\n") && !lower.startsWith("with ")
        && !lower.startsWith("with\n")) || lower.startsWith("with recursive")) {
      throw new IllegalArgumentException("版本化事实查询只接受单条只读 SELECT");
    }
    List<String> ctes = new ArrayList<>(VersionedDefinitionSql.ctes(selection, sql));
    List<Object> parameters = new ArrayList<>();
    for (PublicationFactTable table : PublicationFactTable.values()) {
      List<FactReadQueryContext> contexts = selection.contexts().stream()
          .filter(context -> context.table() == table).toList();
      if (contexts.isEmpty()) {
        if (java.util.regex.Pattern.compile("\\b" + table.tableName() + "\\b").matcher(lower).find()
            || table == PublicationFactTable.MERGE_REQUEST && lower.contains("code_review_formal_records")) {
          throw new IllegalArgumentException("事实查询未绑定所需版本：" + table);
        }
        continue;
      }
      List<String> predicates = new ArrayList<>();
      for (FactReadQueryContext context : contexts) {
        SqlFilter visible = FactReadVisibility.visibleFacts("base_fact", context);
        predicates.add("(base_fact.source_instance = ? and (" + visible.sql() + "))");
        parameters.add(context.sourceInstance());
        parameters.addAll(visible.args());
      }
      ctes.add(table.tableName() + " as (select base_fact.* from " + table.tableName()
          + " base_fact where " + String.join(" or ", predicates) + ")");
      if (table == PublicationFactTable.ISSUE) {
        ctes.add("issue_fact_customer_members as (select member.* from issue_fact_customer_members member"
            + " join issue_fact parent on parent.id = member.issue_fact_revision_id)");
      } else if (table == PublicationFactTable.MERGE_REQUEST) {
        ctes.add("merge_request_commit_fact as (select relation.* from merge_request_commit_fact relation"
            + " join merge_request_fact parent on parent.id = relation.merge_request_revision_id)");
        if (lower.contains("code_review_formal_records")) {
          ctes.add("code_review_formal_records as (" + formalMergeRequestView() + ")");
        }
      }
    }
    parameters.addAll(businessArguments);
    String body = lower.startsWith("with") ? sql.substring(4).stripLeading() : sql;
    return new Query("with " + String.join(", ", ctes) + (lower.startsWith("with") ? ", " : " ") + body,
        parameters);
  }

  private static String formalMergeRequestView() {
    return """
        with promoted_sources as (
          select distinct lower(coalesce(source_instance, '')) as business_source from merge_request_fact
           where source_system = 'LEGACY_PLATFORM' and not deleted
             and lower(coalesce(source_instance, '')) in ('cc', 'dgm')
        ), scoped as (
          select fact.*, case when fact.source_system = 'LEGACY_PLATFORM'
            then lower(coalesce(fact.source_instance, 'cc'))
            when lower(coalesce(fact.source_instance, 'default')) = 'dgm' then 'dgm'
            else 'cc' end as business_source from merge_request_fact fact where not fact.deleted
        ) select scoped.* from scoped
           where (source_system = 'LEGACY_PLATFORM' and business_source in ('cc', 'dgm'))
              or (source_system <> 'LEGACY_PLATFORM' and business_source = 'cc'
                and lower(coalesce(source_instance, 'default')) = 'default' and project_id = 9
                and not exists(select 1 from promoted_sources where business_source = 'cc'))
              or (source_system <> 'LEGACY_PLATFORM' and business_source = 'dgm'
                and lower(coalesce(source_instance, '')) = 'dgm'
                and not exists(select 1 from promoted_sources where business_source = 'dgm'))
        """;
  }

  /** 完整绑定后的只读 SQL，不允许调用者复用可变参数集合。 */
  public record Query(String sql, List<Object> arguments) {
    public Query {
      arguments = java.util.Collections.unmodifiableList(new ArrayList<>(arguments));
    }
  }
}
