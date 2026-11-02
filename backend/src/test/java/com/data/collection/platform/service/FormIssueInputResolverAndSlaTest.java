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

/** 议题九项选择器、原因投影与统一 SLA 判定的纯单元回归。 */
class FormIssueInputResolverAndSlaTest {
  private static final LocalDateTime ISSUE_CREATED = LocalDateTime.of(2026, 11, 1, 0, 0);
  private static final Map<String, String> CAUSE_LABELS =
      Map.of("standard_error", "编码规范错误", "design_forget", "功能设计遗漏");

  @Test
  void emptyDirectoryYieldsEmptyInputWithoutCommentFallback() {
    IssueBusinessInput input = select(List.of(), List.of());

    assertThat(input.responseAdopted()).isTrue();
    assertThat(input.replyAdopted()).isTrue();
    assertThat(input.response().present()).isFalse();
    assertThat(input.reply().present()).isFalse();
    assertThat(input.response().firstResponseAt()).isNull();
    assertThat(input.response().plannedResolutionAt()).isNull();
    assertThat(input.response().planExempt()).isFalse();
    assertThat(input.reply().fixUser()).isNull();
  }

  @Test
  void latestResponseIsTakenAsWholeByCreationTimeAndRecordIdNotByEditTime() {
    LocatedRecord earlierEditedLast =
        response(11L, LocalDateTime.of(2026, 11, 9, 10, 0), LocalDate.of(2026, 11, 9),
            "CC2026R1", LocalDateTime.of(2026, 11, 20, 8, 0));
    LocatedRecord laterPlan = response(12L, LocalDateTime.of(2026, 11, 10, 9, 0),
        LocalDate.of(2026, 11, 15), "CC2026R2", LocalDateTime.of(2026, 11, 10, 9, 0));

    IssueBusinessInput input = select(List.of(earlierEditedLast, laterPlan), List.of());

    assertThat(input.response().present()).isTrue();
    assertThat(input.response().firstResponseAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
    assertThat(input.response().plannedResolutionAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(input.response().plannedMergeVersionBranch()).isEqualTo("CC2026R2");
    assertThat(input.response().plannedResolutionText()).isEqualTo("2026-11-15");
  }

  @Test
  void latestResponseMissingPlanDoesNotBorrowFromEarlierWholeResponse() {
    LocatedRecord earlierPlan = response(11L, LocalDateTime.of(2026, 11, 9, 10, 0),
        LocalDate.of(2026, 11, 9), "CC2026R1", LocalDateTime.of(2026, 11, 9, 10, 0));
    LocatedRecord latestWithoutPlan = response(12L, LocalDateTime.of(2026, 11, 10, 9, 0),
        null, null, LocalDateTime.of(2026, 11, 10, 9, 0));

    IssueBusinessInput input = select(List.of(earlierPlan, latestWithoutPlan), List.of());

    assertThat(input.response().plannedResolutionAt()).isNull();
    assertThat(input.response().plannedResolutionText()).isEmpty();
    assertThat(input.response().plannedMergeVersionBranch()).isNull();
    assertThat(input.response().planExempt())
        .as("D-21 豁免允许向更早响应查合法计划，但计划字段本身不拼接")
        .isTrue();
  }

  @Test
  void sameCreationTimeIsOrderedByRecordIdAndLatestReplyProvidesFixUser() {
    LocatedRecord first = reply(21L, LocalDateTime.of(2026, 11, 12, 9, 0), "account-a");
    LocatedRecord second = reply(22L, LocalDateTime.of(2026, 11, 12, 9, 0), "account-b");

    IssueBusinessInput input = FormIssueInputResolver.select(
        true, List.of(), true, List.of(first, second),
        account -> account.equals("account-b") ? "甲" : "乙",
        record -> CAUSE_LABELS);

    assertThat(input.reply().present()).isTrue();
    assertThat(input.reply().fixUser()).isEqualTo("甲");
    assertThat(input.response().planExempt()).isFalse();
  }

  @Test
  void planInsideEighteenDaysBecomesDeadlineAndKeepsResolveNotDelayed() {
    IssueBusinessInput input = select(
        List.of(response(11L, LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDate.of(2026, 11, 15), "CC2026R1", LocalDateTime.of(2026, 11, 5, 9, 0))),
        List.of());

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        sla(input), List.of(), List.of(), ISSUE_CREATED, "P1",
        LocalDateTime.of(2026, 10, 30, 12, 0), true);

    assertThat(evaluation.resolveDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(evaluation.resolveSlaDays()).isEqualTo(14);
    assertThat(evaluation.resolveDelayed()).isFalse();
    assertThat(evaluation.responseDelayed())
        .as("存在合法计划时间即为 D-21 响应豁免")
        .isFalse();
  }

  @Test
  void planBeyondEighteenDaysFallsBackToDefaultDeadline() {
    IssueBusinessInput input = select(
        List.of(response(11L, LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDate.of(2026, 12, 5), "CC2026R1", LocalDateTime.of(2026, 11, 5, 9, 0))),
        List.of());

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        sla(input), List.of(), List.of(), ISSUE_CREATED, "P1",
        LocalDateTime.of(2026, 10, 30, 12, 0), true);

    assertThat(evaluation.resolveDeadlineAt()).isEqualTo(ISSUE_CREATED.plusDays(18));
    assertThat(evaluation.resolveSlaDays()).isEqualTo(18);
  }

  @Test
  void noPlanUsesDefaultDeadlineAndReplyExemptsResponseDelay() {
    IssueBusinessInput input = select(
        List.of(), List.of(reply(21L, LocalDateTime.of(2026, 11, 6, 9, 0), "account-a")));

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        sla(input), List.of("响应已延期"), List.of(), ISSUE_CREATED, "P1",
        LocalDateTime.of(2026, 11, 20, 12, 0), true);

    assertThat(evaluation.resolveDeadlineAt()).isEqualTo(ISSUE_CREATED.plusDays(18));
    assertThat(evaluation.responseDelayed()).isFalse();
    assertThat(evaluation.resolveDelayed()).isTrue();
  }

  @Test
  void closedIssueNeverProducesDelay() {
    IssueBusinessInput input = select(List.of(), List.of());

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        sla(input), List.of("响应已延期"), List.of(), ISSUE_CREATED, "P1",
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
    IssueBusinessInput input = select(
        List.of(response(11L, LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDate.of(2026, 11, 15), "CC2026R1", LocalDateTime.of(2026, 11, 5, 9, 0))),
        List.of());

    IssueBusinessProjector.apply(fact, input, List.of(), LocalDateTime.of(2026, 10, 30, 12, 0));

    assertThat(fact.getHasResponse()).isTrue();
    assertThat(fact.getResearchTemplateTime()).isEqualTo(LocalDateTime.of(2026, 11, 5, 9, 0));
    assertThat(fact.getPlannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(fact.getPlannedMergeVersionBranch()).isEqualTo("CC2026R1");
    assertThat(fact.getResolveDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(fact.getResponseDelayed()).isFalse();
    assertThat(fact.getResolveDelayed()).isFalse();

    IssueBusinessProjector.apply(
        fact, IssueBusinessInput.empty(true, true), List.of(),
        LocalDateTime.of(2026, 10, 30, 12, 0));

    assertThat(fact.getHasResponse()).isFalse();
    assertThat(fact.getResearchTemplateTime()).isNull();
    assertThat(fact.getPlannedResolutionAt()).isNull();
    assertThat(fact.getPlannedResolutionText()).isEmpty();
    assertThat(fact.getFixUser()).isNull();
  }

  @Test
  void replyCauseKeysProjectStableLabelsAndReplaceCommentTemplateReasons() {
    IssueFact fact = commentFact();
    fact.setReasonCategory("旧评论原因段");
    fact.setIllegalReasons("未按照模板回复");
    fact.setIllegalReason("未按照模板回复");
    fact.setIllegal(true);
    IssueBusinessInput input = select(
        List.of(), List.of(replyWithCauses(21L, List.of("design_forget"))));

    IssueBusinessProjector.apply(fact, input, List.of(), LocalDateTime.of(2026, 11, 20, 12, 0));

    assertThat(fact.getReasonCategory()).isEqualTo("功能设计遗漏");
    assertThat(fact.getIllegalReasons()).doesNotContain("未按照模板回复");
    assertThat(fact.getIllegal()).isFalse();
  }

  @Test
  void zeroOrMultipleDistinctCauseKeysAreNonUniqueButEmptyReplyClearsCommentReasons() {
    IssueFact multiple = commentFact();
    multiple.setIllegalReasons("未设定模块");
    IssueBusinessProjector.apply(
        multiple,
        select(List.of(), List.of(replyWithCauses(21L, List.of("design_forget", "standard_error")))),
        List.of(), LocalDateTime.of(2026, 11, 20, 12, 0));
    assertThat(multiple.getIllegalReasons()).contains("未设定模块", "缺陷原因不唯一");

    IssueFact zero = commentFact();
    zero.setReasonCategory("旧评论原因段");
    zero.setIllegalReasons("未设定模块, 缺陷原因不唯一");
    IssueBusinessProjector.apply(
        zero, select(List.of(), List.of(replyWithCauses(22L, List.of()))),
        List.of(), LocalDateTime.of(2026, 11, 20, 12, 0));
    assertThat(zero.getReasonCategory()).isEmpty();
    assertThat(zero.getIllegalReasons()).contains("未设定模块", "缺陷原因不唯一");

    IssueFact noReply = commentFact();
    noReply.setReasonCategory("旧评论原因段");
    noReply.setIllegalReasons("未设定模块, 未按照模板回复");
    IssueBusinessProjector.apply(
        noReply, select(List.of(), List.of()), List.of(),
        LocalDateTime.of(2026, 11, 20, 12, 0));
    assertThat(noReply.getReasonCategory()).isEmpty();
    assertThat(noReply.getIllegalReasons())
        .as("无回复时不沿用旧评论原因，也不凭空判定不唯一")
        .isEqualTo("未设定模块");
  }

  @Test
  void duplicateStableKeysCountAsOneForUniquenessAndResponseAdoptionClearsResearchTemplateReason() {
    IssueFact fact = commentFact();
    fact.setIllegalReasons("未按照要求填写缺陷调研模板, 未设定模块");
    IssueBusinessInput input = select(
        List.of(response(11L, LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDate.of(2026, 11, 15), "CC2026R1", LocalDateTime.of(2026, 11, 5, 9, 0))),
        List.of(replyWithCauses(21L, List.of("standard_error", "standard_error"))));

    IssueBusinessProjector.apply(fact, input, List.of(), LocalDateTime.of(2026, 11, 20, 12, 0));

    assertThat(fact.getReasonCategory()).isEqualTo("编码规范错误");
    assertThat(fact.getIllegalReasons())
        .as("响应采用表单后不再保留评论调研模板原因；同 key 去重后唯一")
        .isEqualTo("未设定模块");
  }

  @Test
  void canonicalPendingMergeMemberWithReplyIsNotDelayed() {
    List<String> labels = List.of("状态：待合并");
    List<String> statusMembers =
        IssueStatusMembers.parse(IssueFactNormalizationRules.normalizeBugStatus(labels, 325L));
    IssueBusinessInput input = select(
        List.of(), List.of(reply(21L, LocalDateTime.of(2026, 11, 6, 9, 0), "account-a")));

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        sla(input), labels, statusMembers, ISSUE_CREATED, "P1",
        LocalDateTime.of(2026, 11, 20, 12, 0), true);

    assertThat(statusMembers).contains("待合并");
    assertThat(evaluation.resolveDelayed())
        .as("规范完成状态成员加有效回复，与正式主线一致判为不延期")
        .isFalse();
  }

  @Test
  void reasonPrefixedDesignAsIsLabelDoesNotCloseTheIssue() {
    List<String> labels = List.of("原因:设计如此");
    List<String> statusMembers =
        IssueStatusMembers.parse(IssueFactNormalizationRules.normalizeBugStatus(labels, 325L));
    IssueBusinessInput input = select(List.of(), List.of());

    IssueSlaRules.Evaluation evaluation = IssueSlaRules.evaluate(
        sla(input), labels, statusMembers, ISSUE_CREATED, "P1",
        LocalDateTime.of(2026, 11, 20, 12, 0), true);

    assertThat(statusMembers).doesNotContain("设计如此", "需求如此");
    assertThat(evaluation.resolveDelayed())
        .as("原因前缀的“设计如此”不是状态成员，不能当作闭环")
        .isTrue();
  }

  @Test
  void commentResponseDoesNotBorrowFormEmptyReplyOrOldCommentReplyForExemption() {
    IssueFact fact = new IssueFact();
    fact.setIssueState("opened");
    fact.setCreatedAtSource(ISSUE_CREATED);
    fact.setPriorityLevel("P1");
    fact.setRawPayload("### 1、修复状态\n已修复");

    IssueBusinessInput input =
        FormIssueInputResolver.select(false, List.of(), true, List.of(), name -> name,
            record -> CAUSE_LABELS);

    IssueSlaRules.Evaluation evaluation = IssueBusinessProjector.evaluate(
        fact, input, List.of(), LocalDateTime.of(2026, 11, 20, 12, 0));

    assertThat(evaluation.responseDelayed())
        .as("响应 COMMENT 无合法计划、回复 FORM 为空时，旧评论回复不得豁免")
        .isTrue();
  }

  private static IssueBusinessInput select(
      List<LocatedRecord> responses, List<LocatedRecord> replies) {
    return FormIssueInputResolver.select(
        true, responses, true, replies, account -> "甲", record -> CAUSE_LABELS);
  }

  private static IssueSlaRules.SlaInput sla(IssueBusinessInput input) {
    return new IssueSlaRules.SlaInput(
        input.responseAdopted() ? input.response().plannedResolutionAt() : null,
        input.response().planExempt(),
        input.reply().present());
  }

  private static IssueFact commentFact() {
    IssueFact fact = new IssueFact();
    fact.setIssueState("opened");
    fact.setCreatedAtSource(ISSUE_CREATED);
    fact.setPriorityLevel("P1");
    fact.setFixed(false);
    fact.setBugStatus("未设定议题状态");
    fact.setRawPayload("旧评论");
    fact.setIllegal(false);
    return fact;
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
    fields.put("cause_keys", "standard_error");
    return located(recordId, createdAt, createdAt, createdBy, fields);
  }

  private static LocatedRecord replyWithCauses(long recordId, List<String> causeKeys) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("fix_status", "PARTIAL");
    fields.put("cause_keys", causeKeys.toArray(String[]::new));
    return located(
        recordId, LocalDateTime.of(2026, 11, 12, 9, 0), LocalDateTime.of(2026, 11, 12, 9, 0),
        "account-a", fields);
  }

  private static LocatedRecord located(
      long recordId, LocalDateTime createdAt, LocalDateTime updatedAt, String createdBy,
      Map<String, Object> fields) {
    return new LocatedRecord(
        recordId, 1, 901L, 1L, 1L, "MATERIALIZED", 501L, createdBy, 1L, "default",
        "http://gitlab.test", 325L, "issue", "81001", null, null,
        "AUTHORITATIVE", "PUBLISHED", false, createdAt, updatedAt, fields);
  }
}
