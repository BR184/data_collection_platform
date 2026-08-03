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
