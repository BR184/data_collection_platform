package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.service.form.FormRecordDirectoryReader.LocatedRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 议题九项选择器与统一 SLA 判定的纯单元回归。 */
class FormIssueInputResolverAndSlaTest {
  private static final LocalDateTime ISSUE_CREATED = LocalDateTime.of(2026, 11, 1, 0, 0);

  @Test
  void emptyDirectoryYieldsEmptyInputWithoutCommentFallback() {
    IssueBusinessInput input = FormIssueInputResolver.select(List.of(), List.of(), name -> name);

    assertThat(input.hasResponse()).isFalse();
    assertThat(input.firstResponseAt()).isNull();
    assertThat(input.plannedResolutionAt()).isNull();
    assertThat(input.responsePlanExempt()).isFalse();
    assertThat(input.hasReply()).isFalse();
    assertThat(input.fixUser()).isNull();
  }

  @Test
  void latestResponseIsTakenAsWholeByCreationTimeAndRecordIdNotByEditTime() {
    LocatedRecord earlierEditedLast =
        response(11L, LocalDateTime.of(2026, 11, 9, 10, 0), LocalDate.of(2026, 11, 9),
            "CC2026R1", LocalDateTime.of(2026, 11, 20, 8, 0));
    LocatedRecord laterPlan = response(12L, LocalDateTime.of(2026, 11, 10, 9, 0),
        LocalDate.of(2026, 11, 15), "CC2026R2", LocalDateTime.of(2026, 11, 10, 9, 0));

    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(earlierEditedLast, laterPlan), List.of(), name -> name);

    assertThat(input.hasResponse()).isTrue();
    assertThat(input.firstResponseAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
    assertThat(input.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(input.plannedMergeVersionBranch()).isEqualTo("CC2026R2");
    assertThat(input.plannedResolutionText()).isEqualTo("2026-11-15");
  }

  @Test
  void latestResponseMissingPlanDoesNotBorrowFromEarlierWholeResponse() {
    LocatedRecord earlierPlan = response(11L, LocalDateTime.of(2026, 11, 9, 10, 0),
        LocalDate.of(2026, 11, 9), "CC2026R1", LocalDateTime.of(2026, 11, 9, 10, 0));
    LocatedRecord latestWithoutPlan = response(12L, LocalDateTime.of(2026, 11, 10, 9, 0),
        null, null, LocalDateTime.of(2026, 11, 10, 9, 0));

    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(earlierPlan, latestWithoutPlan), List.of(), name -> name);

    assertThat(input.plannedResolutionAt()).isNull();
    assertThat(input.plannedResolutionText()).isEmpty();
    assertThat(input.plannedMergeVersionBranch()).isNull();
    assertThat(input.responsePlanExempt())
        .as("D-21 豁免允许向更早响应查合法计划，但计划字段本身不拼接")
        .isTrue();
  }

  @Test
  void sameCreationTimeIsOrderedByRecordIdAndLatestReplyProvidesFixUser() {
    LocatedRecord first = reply(21L, LocalDateTime.of(2026, 11, 12, 9, 0), "account-a");
    LocatedRecord second = reply(22L, LocalDateTime.of(2026, 11, 12, 9, 0), "account-b");

    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(), List.of(first, second), account -> account.equals("account-b") ? "甲" : "乙");

    assertThat(input.hasReply()).isTrue();
    assertThat(input.fixUser()).isEqualTo("甲");
    assertThat(input.responsePlanExempt()).isFalse();
  }

  @Test
  void planInsideEighteenDaysBecomesDeadlineAndKeepsResolveNotDelayed() {
    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(response(11L, LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDate.of(2026, 11, 15), "CC2026R1", LocalDateTime.of(2026, 11, 5, 9, 0))),
        List.of(), name -> name);

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        input, List.of(), false, ISSUE_CREATED, "P1", LocalDateTime.of(2026, 10, 30, 12, 0), true);

    assertThat(evaluation.resolveDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(evaluation.resolveSlaDays()).isEqualTo(14);
    assertThat(evaluation.resolveDelayed()).isFalse();
    assertThat(evaluation.responseDelayed())
        .as("存在合法计划时间即为 D-21 响应豁免")
        .isFalse();
  }

  @Test
  void planBeyondEighteenDaysFallsBackToDefaultDeadline() {
    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(response(11L, LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDate.of(2026, 12, 5), "CC2026R1", LocalDateTime.of(2026, 11, 5, 9, 0))),
        List.of(), name -> name);

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        input, List.of(), false, ISSUE_CREATED, "P1", LocalDateTime.of(2026, 10, 30, 12, 0), true);

    assertThat(evaluation.resolveDeadlineAt()).isEqualTo(ISSUE_CREATED.plusDays(18));
    assertThat(evaluation.resolveSlaDays()).isEqualTo(18);
  }

  @Test
  void noPlanUsesDefaultDeadlineAndReplyExemptsResponseDelay() {
    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(), List.of(reply(21L, LocalDateTime.of(2026, 11, 6, 9, 0), "account-a")),
        account -> "甲");

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        input, List.of("响应已延期"), false, ISSUE_CREATED, "P1",
        LocalDateTime.of(2026, 11, 20, 12, 0), true);

    assertThat(evaluation.resolveDeadlineAt()).isEqualTo(ISSUE_CREATED.plusDays(18));
    assertThat(evaluation.responseDelayed()).isFalse();
    assertThat(evaluation.resolveDelayed()).isTrue();
  }

  @Test
  void closedIssueNeverProducesDelay() {
    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(), List.of(), name -> name);

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        input, List.of("响应已延期"), false, ISSUE_CREATED, "P1",
        LocalDateTime.of(2026, 12, 1, 0, 0), false);

    assertThat(evaluation.responseDelayed()).isFalse();
    assertThat(evaluation.resolveDelayed()).isFalse();
  }

  @Test
  void projectorWritesWholeResponseColumnsAndEmptyInputClearsCommentValues() {
    IssueFact fact = new IssueFact();
    fact.setIssueState("opened");
    fact.setCreatedAtSource(ISSUE_CREATED);
    fact.setPriorityLevel("P1");
    fact.setFixed(false);
    IssueBusinessInput input = FormIssueInputResolver.select(
        List.of(response(11L, LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDate.of(2026, 11, 15), "CC2026R1", LocalDateTime.of(2026, 11, 5, 9, 0))),
        List.of(), name -> name);

    IssueBusinessProjector.apply(fact, input, List.of(), LocalDateTime.of(2026, 10, 30, 12, 0));

    assertThat(fact.getHasResponse()).isTrue();
    assertThat(fact.getResearchTemplateTime()).isEqualTo(LocalDateTime.of(2026, 11, 5, 9, 0));
    assertThat(fact.getPlannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(fact.getPlannedMergeVersionBranch()).isEqualTo("CC2026R1");
    assertThat(fact.getResolveDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(fact.getResponseDelayed()).isFalse();
    assertThat(fact.getResolveDelayed()).isFalse();

    IssueBusinessProjector.apply(
        fact, IssueBusinessInput.empty(), List.of(), LocalDateTime.of(2026, 10, 30, 12, 0));

    assertThat(fact.getHasResponse()).isFalse();
    assertThat(fact.getResearchTemplateTime()).isNull();
    assertThat(fact.getPlannedResolutionAt()).isNull();
    assertThat(fact.getPlannedResolutionText()).isEmpty();
    assertThat(fact.getFixUser()).isNull();
  }

  private static LocatedRecord response(
      long recordId, LocalDateTime createdAt, LocalDate planDate, String branch,
      LocalDateTime updatedAt) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("planned_resolution_date", planDate);
    fields.put("planned_merge_version_branch", branch);
    return located(recordId, createdAt, updatedAt, "account-a", fields);
  }

  private static LocatedRecord reply(
      long recordId, LocalDateTime createdAt, String createdBy) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("fix_status", "PARTIAL");
    return located(recordId, createdAt, createdAt, createdBy, fields);
  }

  private static LocatedRecord located(
      long recordId, LocalDateTime createdAt, LocalDateTime updatedAt, String createdBy,
      Map<String, Object> fields) {
    return new LocatedRecord(
        recordId, 901L, 1L, 1L, "MATERIALIZED", 501L, createdBy, 1L, "default",
        "http://gitlab.test", 325L, "issue", "81001", null, null,
        "AUTHORITATIVE", "PUBLISHED", false, createdAt, updatedAt, fields);
  }
}
