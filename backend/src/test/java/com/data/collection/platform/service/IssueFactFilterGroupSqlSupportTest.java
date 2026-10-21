package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueFactFilterGroupSqlSupportTest {

  @Test
  void test_combinedStatus_labelGroup_sqlSplitsStatusMembers() {
    StatisticFilterCondition condition =
        new StatisticFilterCondition(
            "bugStatus",
            "intersects",
            null,
            null,
            "LABEL_GROUP",
            8L,
            "状态组",
            List.of("历史遗留", "申请延期"));

    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toSql(new StatisticFilterGroup("AND", List.of(condition)))
            .orElseThrow();

    assertThat(predicate.predicate()).contains("regexp_split_to_table");
    assertThat(predicate.predicate()).contains("[、，,&]");
    assertThat(predicate.args()).containsExactly("历史遗留", "申请延期");
  }

  @Test
  void test_resolvedStatus_sqlKeepsSlashInOneStatusMember() {
    StatisticFilterCondition condition =
        new StatisticFilterCondition("bugStatus", "eq", "已修复/完成", null);

    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toSql(new StatisticFilterGroup("AND", List.of(condition)))
            .orElseThrow();

    assertThat(predicate.predicate()).contains("regexp_split_to_table");
    assertThat(predicate.args()).containsExactly("已修复/完成");
  }

  @Test
  void test_customerIssueFields_useDisplayedFactColumns() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND",
            List.of(
                new StatisticFilterCondition("functionName", "contains", "装配", null),
                new StatisticFilterCondition("testingPhase", "eq", "新增需求", null),
                new StatisticFilterCondition("fixUser", "eq", "李四", null),
                new StatisticFilterCondition("delayCause", "eq", "需求变更", null)));

    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toCustomerIssueSql(filterGroup).orElseThrow();

    assertThat(predicate.predicate()).contains("lower(coalesce(function_name, '')) like ?");
    assertThat(predicate.predicate()).contains("lower(coalesce(testing_phase, '')) = ?");
    assertThat(predicate.predicate()).contains("lower(coalesce(fix_user, '')) = ?");
    assertThat(predicate.predicate()).contains("regexp_split_to_table");
    assertThat(predicate.predicate()).contains("delay_cause");
    assertThat(predicate.args()).containsExactly("%装配%", "新增需求", "李四", "需求变更");
  }

  @Test
  void test_systemTestDelayCause_keepsLabelFallbackInMemory() {
    StatisticFilterCondition condition =
        new StatisticFilterCondition("delayCause", "eq", "技术卡点", null);

    assertThat(
            IssueFactFilterGroupSqlSupport.toSql(
                new StatisticFilterGroup("AND", List.of(condition))))
        .isEmpty();
  }

  @Test
  void test_byFunctionDeclaredFields_everyDeclaredOperatorCompilesInsideOneGroup() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "OR",
            List.of(
                new StatisticFilterCondition("severityLevel", "eq", "LEVEL1", null),
                new StatisticFilterCondition("projectName", "contains", "CC_Product", null),
                new StatisticFilterCondition("functionName", "eq", "草图约束", null),
                new StatisticFilterCondition("moduleName", "isEmpty", null, null)));

    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toCustomerIssueSql(filterGroup).orElseThrow();

    // OR 组保持“满足任意”，不能逐条降级成 AND；已声明的每个关系都必须可下推，否则整组会被丢弃。
    assertThat(predicate.predicate())
        .contains(" or ")
        .contains("lower(coalesce(severity_level, '')) = ?")
        .contains("lower(coalesce(project_name, '')) like ?")
        .contains("lower(coalesce(function_name, '')) = ?")
        .contains("nullif(btrim(coalesce(module_names, '')), '') is null");
    // 条件值里的下划线是字面文本，转义后仍只匹配真实含下划线的项目名。
    assertThat(predicate.args()).containsExactly("level1", "%cc\\_product%", "草图约束");
  }

  @Test
  void test_andGroupKeepsEveryConditionAsIntersection() {
    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toCustomerIssueSql(
                new StatisticFilterGroup(
                    "AND",
                    List.of(
                        new StatisticFilterCondition("severityLevel", "ne", "SUGGESTION", null),
                        new StatisticFilterCondition("functionName", "contains", "装配", null))))
            .orElseThrow();

    assertThat(predicate.predicate())
        .contains(") and (")
        .doesNotContain(" or ");
    assertThat(predicate.args()).containsExactly("suggestion", "%装配%");
  }

  @Test
  void test_containsLiteralWildcards_areEscapedWithExplicitEscapeClause() {
    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toCustomerIssueSql(
                new StatisticFilterGroup(
                    "AND",
                    List.of(new StatisticFilterCondition("functionName", "contains", "钣金%_\\", null))))
            .orElseThrow();

    assertThat(predicate.predicate())
        .contains("lower(coalesce(function_name, '')) like ? escape '\\'");
    // 反斜线是 ESCAPE 符本身，必须最先自身转义；% 与 _ 只能作为字面文本参与匹配。
    assertThat(predicate.args()).containsExactly("%钣金\\%\\_\\\\%");
  }

  @Test
  void test_blankContainsValue_neverDegradesIntoFullTableMatch() {
    assertThat(compile("AND", new StatisticFilterCondition("functionName", "contains", "  ", null)))
        .contains("1 = 0");
    assertThat(compile("AND", new StatisticFilterCondition("functionName", "notContains", "  ", null)))
        .contains("1 = 1");
  }

  @Test
  void test_moduleEquality_comparesTrimmedMembersAcrossBothSeparators() {
    SqlPredicate equals =
        IssueFactFilterGroupSqlSupport.toCustomerIssueSql(
                new StatisticFilterGroup(
                    "AND", List.of(new StatisticFilterCondition("moduleName", "eq", " 工具 ", null))))
            .orElseThrow();

    assertThat(equals.predicate())
        .contains("regexp_split_to_table(coalesce(module_names, ''), '\\s*[,&]\\s*')")
        .contains("lower(btrim(filter_modules.module_name)) = ?")
        .doesNotContain("not (");
    assertThat(equals.args()).containsExactly("工具");

    assertThat(compile("AND", new StatisticFilterCondition("moduleName", "ne", "工具", null)))
        .contains("not (exists");

    // 空白成员值与引擎一致：等值恒假、不等恒真，绝不允许退化成 “%,%” 这类通配匹配。
    assertThat(compile("AND", new StatisticFilterCondition("moduleName", "eq", " ", null)))
        .contains("1 = 0");
    assertThat(compile("AND", new StatisticFilterCondition("moduleName", "ne", " ", null)))
        .contains("1 = 1");
  }

  private static String compile(String logic, StatisticFilterCondition... conditions) {
    return IssueFactFilterGroupSqlSupport.toCustomerIssueSql(
            new StatisticFilterGroup(logic, List.of(conditions)))
        .map(SqlPredicate::predicate)
        .orElse("");
  }

  @Test
  void test_customerName_usesMembershipRelationInsteadOfDisplayProjection() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND", List.of(new StatisticFilterCondition("customerName", "eq", "郑州新世纪", null)));

    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toCustomerIssueSql(filterGroup).orElseThrow();

    assertThat(predicate.predicate())
        .contains("exists (select 1 from issue_fact_customer_members customer_member")
        .contains("customer_member.customer_name");
    assertThat(predicate.args()).containsExactly("郑州新世纪");
  }
}
