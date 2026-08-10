package com.data.collection.platform.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import com.data.collection.platform.bi.domain.source.BiSystemTestSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiSystemTestCalculatorTest {
  private final BiSystemTestCalculator calculator = new BiSystemTestCalculator();

  @Test
  void returnsEveryFrontendSectionContractWhenSystemTestSourceIsEmpty() {
    var response = calculator.calculate(new BiSystemTestSource(
        "issue-source-empty", "issue-snapshot-empty", List.of(), List.of()));

    assertThat(response.status()).isEqualTo(BiDataStatus.EMPTY);
    assertThat(response.sections()).extracting("key")
        .contains(
            "round-quality",
            "severity-distribution",
            "module-quality",
            "module-repair-targets",
            "delay-analysis",
            "developer-workload",
            "cause-distribution");
  }

  @Test
  void calculatesIndependentTargetsAndProductLevelDeduplication() {
    BiSystemTestSource source = source(
        List.of(
            issue(1L, "round-1", "LEVEL1", "P1", true, List.of(module("草图")), "张三"),
            issue(2L, "round-1", "LEVEL1", "P1", false, List.of(module("草图")), "李四"),
            issue(3L, "round-2", "LEVEL2", "P2", true, List.of(module("草图"), module("装配")), "张三"),
            issue(3L, "round-2", "LEVEL2", "P2", true, List.of(module("草图"), module("装配")), "张三")));

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.READY);
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("module-repair-targets");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
    assertThat(response.data().overview().totalCount()).isEqualTo(3L);
    assertThat(response.data().overview().fixedCount()).isEqualTo(2L);
    assertThat(response.data().qualityTargets()).extracting("key", "targetRate", "achieved")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("level-one", new java.math.BigDecimal("100.00"), false),
            org.assertj.core.groups.Tuple.tuple("p1", new java.math.BigDecimal("90.00"), false),
            org.assertj.core.groups.Tuple.tuple("p2", new java.math.BigDecimal("80.00"), true));
    assertThat(response.data().modules()).filteredOn(module -> module.module().displayName().equals("草图"))
        .first().extracting("totalCount").isEqualTo(3L);
    assertThat(response.data().modules()).filteredOn(module -> module.module().displayName().equals("装配"))
        .first().extracting("totalCount").isEqualTo(1L);
    assertThat(response.data().modules()).filteredOn(module -> module.module().displayName().equals("草图"))
        .first().extracting("fixRate", "levelOneFixRate", "p1FixRate", "p2FixRate")
        .containsExactly(
            new java.math.BigDecimal("66.67"),
            new java.math.BigDecimal("50.00"),
            new java.math.BigDecimal("50.00"),
            new java.math.BigDecimal("100.00"));
    assertThat(response.data().rounds()).extracting("submittedCount")
        .containsExactly(2L, 1L);
  }

  @Test
  void keepsMissingDimensionsAsExplicitUnknownGroupsWithoutHidingAvailableFacts() {
    BiSystemTestSource source = source(
        List.of(issue(4L, "round-1", "LEVEL2", "P1", false,
            List.of(BiSourceDimension.unknown("未标注模块")), null)));

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.READY);
    assertThat(response.data().modules()).extracting(
        item -> item.module().displayName(), item -> item.totalCount())
        .containsExactly(org.assertj.core.groups.Tuple.tuple("未标注模块", 1L));
    assertThat(response.data().causeCategories()).extracting("categoryName", "count")
        .containsExactly(org.assertj.core.groups.Tuple.tuple("设计问题", 1L));
    assertThat(response.data().delays()).extracting("reason", "severity", "count")
        .containsExactly(org.assertj.core.groups.Tuple.tuple("资源卡点", "LEVEL2", 1L));
    assertThat(response.data().developers()).extracting(
        item -> item.assignee().displayName(), item -> item.totalCount())
        .containsExactly(org.assertj.core.groups.Tuple.tuple("未指派", 1L));
    assertThat(response.data().rounds()).extracting("roundId", "submittedCount")
        .containsExactly(org.assertj.core.groups.Tuple.tuple("round-1", 1L));
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("cause-distribution");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
  }

  @Test
  void exposesVerifiedSystemTestPhysicalFieldsInTrace() {
    var response = calculator.calculate(source(
        List.of(issue(11L, "round-1", "LEVEL2", "P1", false,
            List.of(module("草图")), "张三"))));

    assertThat(response.traces()).flatExtracting(trace -> trace.sourceFields())
        .extracting("englishName")
        .contains(
            "issue_fact.module_names",
            "issue_fact.assignee_name",
            "issue_fact.reason_category",
            "issue_fact.delay_issue")
        .doesNotContain("moduleId", "delayApplicationStatus");
  }

  @Test
  void marksAbsentTargetPopulationNotApplicableInsteadOfFailed() {
    BiSystemTestSource source = source(
        List.of(issue(5L, "round-1", "LEVEL3", "P3", true, List.of(module("草图")), "张三")));

    var response = calculator.calculate(source);

    assertThat(response.data().qualityTargets()).allSatisfy(target -> {
      assertThat(target.status()).isEqualTo(BiDataStatus.NOT_APPLICABLE);
      assertThat(target.fixRate()).isNull();
      assertThat(target.achieved()).isNull();
    });
  }

  @Test
  void keepsKnownPriorityTargetsWhenAnotherIssueHasNoPriority() {
    BiSystemTestSource source = source(
        List.of(
            issue(6L, "round-1", "LEVEL1", null, true,
                List.of(module("草图")), "张三"),
            issue(7L, "round-1", "LEVEL2", "P1", false,
                List.of(module("草图")), "李四")));

    var response = calculator.calculate(source);

    assertThat(response.data().qualityTargets()).filteredOn(target -> target.key().equals("level-one"))
        .first().extracting("status").isEqualTo(BiDataStatus.READY);
    assertThat(response.data().qualityTargets()).filteredOn(target -> target.key().equals("p1") || target.key().equals("p2"))
        .extracting("key", "status", "totalCount", "fixedCount")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("p1", BiDataStatus.READY, 1L, 0L),
            org.assertj.core.groups.Tuple.tuple("p2", BiDataStatus.NOT_APPLICABLE, 0L, 0L));
  }

  @Test
  void keepsModulePriorityRatesWhenAnotherIssueHasNoSeverity() {
    BiSystemTestSource source = source(
        List.of(
            issue(61L, "round-1", null, "P1", true,
                List.of(module("草图")), "张三"),
            issue(62L, "round-1", "LEVEL2", "P2", false,
                List.of(module("草图")), "李四")));

    var response = calculator.calculate(source);

    assertThat(response.data().modules()).singleElement().satisfies(module -> {
      assertThat(module.module().displayName()).isEqualTo("草图");
      assertThat(module.levelOneFixRate()).isNull();
      assertThat(module.p1FixRate()).isEqualByComparingTo("100.00");
      assertThat(module.p2FixRate()).isEqualByComparingTo("0.00");
    });
    assertThat(response.sections()).filteredOn(section -> section.key().equals("module-repair-targets"))
        .first().extracting("status").isEqualTo(BiDataStatus.INCOMPLETE);
  }

  @Test
  void rejectsConflictingIssueIdsInsteadOfUsingTheFirstIssue() {
    BiSystemTestSource source = source(
        List.of(
            issue(7L, "round-1", "LEVEL2", "P2", true,
                List.of(module("草图")), "张三"),
            issue(7L, "round-1", "LEVEL2", "P2", false,
                List.of(module("草图")), "张三")));

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data()).isNull();
    assertThat(response.sections()).extracting("key")
        .contains("source-consistency", "round-quality", "module-quality", "delay-analysis");
    assertThat(response.sections()).allSatisfy(section ->
        assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE));
  }

  @Test
  void rejectsDuplicateRoundIdsAndOrders() {
    BiSystemTestSource source = new BiSystemTestSource(
        "issue-source-2",
        "issue-snapshot-2",
        List.of(
            new BiSystemTestSource.RoundDefinition("round-1", "第一轮", 1),
            new BiSystemTestSource.RoundDefinition("round-1", "重复轮次", 1)),
        List.of(issue(8L, "round-1", "LEVEL2", "P2", true,
            List.of(module("草图")), "张三")));

    var response = calculator.calculate(source);

    assertThat(response.data().rounds()).isEmpty();
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("round-quality");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    });
  }

  @Test
  void calculatesRoundSubmissionCountsFromDistinctIssuesAndPreservesConservation() {
    BiSystemTestSource source = source(
        List.of(
            issue(9L, "round-1", "LEVEL1", "P1", true,
                List.of(module("草图")), "张三"),
            issue(12L, "round-1", "LEVEL2", "P2", false,
                List.of(module("装配")), "李四"),
            issue(12L, "round-1", "LEVEL2", "P2", false,
                List.of(module("装配")), "李四")));

    var response = calculator.calculate(source);

    assertThat(response.data().rounds()).filteredOn(round -> round.roundId().equals("round-1"))
        .singleElement().satisfies(round -> {
          assertThat(round.submittedCount()).isEqualTo(2L);
          assertThat(round.levelOneCount() + round.levelTwoCount() + round.levelThreeCount())
              .isEqualTo(round.submittedCount());
          assertThat(round.closedCount() + round.openCount()).isEqualTo(round.submittedCount());
        });
    assertThat(response.sections()).filteredOn(section -> section.key().equals("round-quality"))
        .singleElement().extracting("status").isEqualTo(BiDataStatus.READY);
  }

  @Test
  void marksRoundQualityIncompleteWhenAnyIssueHasNoLegalSeverity() {
    BiSystemTestSource source = source(
        List.of(issue(13L, "round-1", null, "P2", false,
            List.of(module("草图")), "张三")));

    var response = calculator.calculate(source);

    assertThat(response.data().rounds()).isEmpty();
    assertThat(response.sections()).filteredOn(section -> section.key().equals("round-quality"))
        .singleElement().satisfies(section -> {
          assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
          assertThat(section.message()).contains("严重级别");
        });
    assertThat(response.sections()).filteredOn(section -> section.key().equals("developer-workload"))
        .singleElement().extracting("status").isEqualTo(BiDataStatus.READY);
    assertThat(response.data().developers()).singleElement().satisfies(developer -> {
      assertThat(developer.assignee().displayName()).isEqualTo("张三");
      assertThat(developer.totalCount()).isEqualTo(1L);
      assertThat(developer.openCount()).isEqualTo(1L);
    });
  }

  @Test
  void rejectsMissingModuleDimension() {
    BiSystemTestSource source = source(
        List.of(issue(10L, "round-1", "LEVEL2", "P2", true, List.of(), "张三")));

    var response = calculator.calculate(source);

    assertThat(response.data().modules()).isEmpty();
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("module-quality");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    });
  }

  private BiSystemTestSource source(List<BiSystemTestSource.IssueRecord> issues) {
    return new BiSystemTestSource(
        "issue-source-1",
        "issue-snapshot-1",
        List.of(
            new BiSystemTestSource.RoundDefinition("round-1", "第一轮", 1),
            new BiSystemTestSource.RoundDefinition("round-2", "第二轮", 2)),
        issues);
  }

  private BiSystemTestSource.IssueRecord issue(
      long issueId,
      String roundId,
      String severity,
      String priority,
      boolean fixed,
      List<BiSourceDimension> modules,
      String assignee) {
    return new BiSystemTestSource.IssueRecord(
        issueId,
        roundId,
        severity,
        priority,
        fixed,
        modules,
        BiSourceDimension.fromNullable(assignee, "未指派"),
        List.of(new BiSystemTestSource.CauseRef("design", "设计问题", "missing", "设计遗漏")),
        true,
        List.of(BiSourceDimension.identified("资源卡点")));
  }

  private BiSourceDimension module(String name) {
    return BiSourceDimension.identified(name);
  }
}
