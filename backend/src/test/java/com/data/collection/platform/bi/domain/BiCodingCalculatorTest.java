package com.data.collection.platform.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.source.BiCodingSource;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiCodingCalculatorTest {
  private final BiCodingCalculator calculator = new BiCodingCalculator();

  @Test
  void returnsEveryFrontendSectionContractWhenCodingSourceIsEmpty() {
    var response = calculator.calculate(source(List.of(), List.of(), List.of(), false, false, false));

    assertThat(response.status()).isEqualTo(BiDataStatus.EMPTY);
    assertThat(response.sections()).extracting("key")
        .contains(
            "code-trend",
            "submission-trend",
            "contributors",
            "module-increments",
            "review-categories",
            "module-review-quality",
            "review-scatter",
            "static-scan",
            "quality-trend");
  }

  @Test
  void calculatesDeduplicatedCodeScaleAndConfirmedReviewMetrics() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(101L, LocalDate.of(2026, 8, 1), "张三", "草图", 1200),
            mergeRequest(102L, LocalDate.of(2026, 8, 2), "李四", "装配", 800),
            mergeRequest(101L, LocalDate.of(2026, 8, 1), "张三", "草图", 1200)),
        List.of(
            review(1L, 101L, LocalDate.of(2026, 8, 1), "草图", 1200, "120", 4, 1, 1, 1, 1, 0),
            review(2L, 102L, LocalDate.of(2026, 8, 2), "装配", 800, "60", 8, 2, 2, 2, 1, 1)),
        List.of(
            new BiCodingSource.CommitRecord("commit-a", LocalDate.of(2026, 8, 1)),
            new BiCodingSource.CommitRecord("commit-b", LocalDate.of(2026, 8, 1))),
        true,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.READY);
    assertThat(response.data().summary().addedLines()).isEqualTo(2000L);
    assertThat(response.data().summary().addedKloc()).isEqualByComparingTo("2.00");
    assertThat(response.data().summary().mergeRequestCount()).isEqualTo(2L);
    assertThat(response.data().summary().contributorCount()).isEqualTo(2L);
    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("6.00");
    assertThat(response.data().summary().reviewSpeedLocPerHour()).isEqualByComparingTo("666.67");
    assertThat(response.data().summary().reviewDensityAchieved()).isTrue();
    assertThat(response.data().codeTrend()).extracting("addedLines", "cumulativeLines")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1200L, 1200L),
            org.assertj.core.groups.Tuple.tuple(800L, 2000L));
    assertThat(response.data().reviewCategories()).extracting("category", "count")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("代码规范", 3L),
            org.assertj.core.groups.Tuple.tuple("代码逻辑规范", 3L),
            org.assertj.core.groups.Tuple.tuple("性能规范", 3L),
            org.assertj.core.groups.Tuple.tuple("设计规范", 2L),
            org.assertj.core.groups.Tuple.tuple("其他", 1L));
    assertThat(response.data().submissionTrend()).first().extracting("commitCount")
        .isEqualTo(2L);
    assertThat(response.data().reviewPoints()).extracting("reviewSpeedKlocPerHour")
        .containsExactly(new BigDecimal("0.60"), new BigDecimal("0.80"));
  }

  @Test
  void groupsContributorsAndModulesByAvailableSourceNamesWithoutSyntheticIds() {
    BiCodingSource source = source(
        List.of(mergeRequest(201L, LocalDate.of(2026, 8, 3), "王五", "工程图", 500)),
        List.of(review(3L, 201L, LocalDate.of(2026, 8, 3), "工程图", 500, "30", 2, 1, 1, 0, 0, 0)),
        List.of(),
        false,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().addedLines()).isEqualTo(500L);
    assertThat(response.data().summary().contributorCount()).isEqualTo(1L);
    assertThat(response.data().contributors()).extracting(
        item -> item.contributor().displayName(), item -> item.addedLines())
        .containsExactly(org.assertj.core.groups.Tuple.tuple("王五", 500L));
    assertThat(response.data().moduleIncrements()).extracting(
        item -> item.module().displayName(), item -> item.addedLines())
        .containsExactly(org.assertj.core.groups.Tuple.tuple("工程图", 500L));
    assertThat(response.data().moduleReviewQuality()).extracting(item -> item.module().displayName())
        .containsExactly("工程图");
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("submission-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    });
  }

  @Test
  void keepsSubmissionFactsIncompleteWhenMergeRequestsExistButNoCommitsWerePublished() {
    BiCodingSource source = source(
        List.of(mergeRequest(203L, LocalDate.of(2026, 8, 3), "王五", "工程图", 500)),
        List.of(),
        List.of(),
        true,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.data().submissionTrend()).isEmpty();
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("submission-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
      assertThat(section.message()).contains("稳定提交 ID").contains("提交时间");
    });
  }

  @Test
  void exposesSeparatedPhysicalSourcesInTrace() {
    var response = calculator.calculate(source(
        List.of(mergeRequest(202L, LocalDate.of(2026, 8, 3), "王五", "工程图", 500)),
        List.of(),
        List.of(),
        false,
        false,
        false));

    assertThat(response.traces()).flatExtracting(trace -> trace.sourceFields())
        .extracting("englishName")
        .contains(
            "code_review_match_mode_records.author_name / code_review_formal_records.author_name",
            "code_review_match_mode_records.module_name / code_review_formal_records.module_name",
            "merge_request_commit_fact.commit_sha",
            "merge_request_commit_fact.committed_at_source",
            "code_review_match_mode_records.code_walkthrough_date / code_review_formal_records.code_walkthrough_date",
            "code_review_match_mode_records.review_duration_minutes / code_review_formal_records.review_duration_minutes",
            "code_review_match_mode_records.defect_count / code_review_formal_records.defect_count",
            "code_review_match_mode_records.scan_status / code_review_formal_records.scan_status",
            "code_review_match_mode_records.comment_rate / code_review_formal_records.comment_rate")
        .doesNotContain(
            "authorId",
            "moduleId");
  }

  @Test
  void keepsMissingReviewDenominatorsUncomputable() {
    BiCodingSource source = source(
        List.of(mergeRequest(301L, LocalDate.of(2026, 8, 4), "张三", "草图", 0)),
        List.of(review(4L, 301L, LocalDate.of(2026, 8, 4), "草图", 0, "0", 1, 1, 0, 0, 0, 0)),
        List.of(),
        true,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().reviewDefectDensity()).isNull();
    assertThat(response.data().summary().reviewSpeedLocPerHour()).isNull();
    assertThat(response.data().summary().reviewDensityAchieved()).isNull();
  }

  @Test
  void keeps_aggregate_review_metrics_when_one_observation_has_zero_denominators() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(311L, LocalDate.of(2026, 8, 4), "张三", "草图", 1000),
            mergeRequest(312L, LocalDate.of(2026, 8, 4), "李四", "草图", 0)),
        List.of(
            review(41L, 311L, LocalDate.of(2026, 8, 4), "草图", 1000, "60", 4, 1, 1, 1, 1, 0),
            review(42L, 312L, LocalDate.of(2026, 8, 4), "草图", 0, "0", 0, 0, 0, 0, 0, 0)),
        List.of(new BiCodingSource.CommitRecord("commit-zero-denominator", LocalDate.of(2026, 8, 4))),
        true,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("4.00");
    assertThat(response.data().summary().reviewSpeedLocPerHour()).isEqualByComparingTo("1000.00");
    assertThat(response.data().moduleReviewQuality()).singleElement().satisfies(module -> {
      assertThat(module.defectDensity()).isEqualByComparingTo("4.00");
      assertThat(module.reviewSpeedLocPerHour()).isEqualByComparingTo("1000.00");
    });
    assertThat(response.data().reviewPoints()).extracting("codeReviewId").containsExactly(41L);
  }

  @Test
  void isolatesConflictingCodeScaleFromIdentityAndReviewFacts() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(401L, LocalDate.of(2026, 8, 5), "张三", "草图", 500),
            mergeRequest(401L, LocalDate.of(2026, 8, 5), "张三", "草图", 800)),
        List.of(review(5L, 401L, LocalDate.of(2026, 8, 5), "草图", 500, "30", 2, 1, 1, 0, 0, 0)),
        List.of(new BiCodingSource.CommitRecord("commit-c", LocalDate.of(2026, 8, 5))),
        true,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().addedLines()).isNull();
    assertThat(response.data().summary().mergeRequestCount()).isEqualTo(1L);
    assertThat(response.data().codeTrend()).isEmpty();
    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("4.00");
    assertThat(response.data().reviewPoints()).hasSize(1);
  }

  @Test
  void rejectsNegativeCodeScaleInsteadOfClampingItToZero() {
    BiCodingSource source = source(
        List.of(mergeRequest(501L, LocalDate.of(2026, 8, 6), "张三", "草图", -5)),
        List.of(),
        List.of(),
        false,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().addedLines()).isNull();
    assertThat(response.data().codeTrend()).isEmpty();
    assertThat(response.data().contributors()).isEmpty();
  }

  @Test
  void excludesConflictingCodeReviewFactsWithoutHidingIndependentCodeScale() {
    BiCodingSource.CodeReviewRecord first = review(
        6L, 601L, LocalDate.of(2026, 8, 7), "草图", 500, "30", 2, 1, 1, 0, 0, 0);
    BiCodingSource.CodeReviewRecord conflicting = review(
        6L, 601L, LocalDate.of(2026, 8, 7), "草图", 500, "30", 3, 1, 1, 1, 0, 0);
    BiCodingSource source = source(
        List.of(mergeRequest(601L, LocalDate.of(2026, 8, 7), "张三", "草图", 500)),
        List.of(first, conflicting),
        List.of(),
        false,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().addedLines()).isEqualTo(500L);
    assertThat(response.data().summary().reviewDefectDensity()).isNull();
    assertThat(response.data().reviewCategories()).isEmpty();
    assertThat(response.data().reviewPoints()).isEmpty();
  }

  @Test
  void omitsDateBasedReviewSeriesWhenReviewDateIsMissing() {
    BiCodingSource.CodeReviewRecord review = new BiCodingSource.CodeReviewRecord(
        7L, identity(701L), null, BiSourceDimension.identified("草图"), 500L,
        new BigDecimal("30"),
        2L, 1L, 1L, 0L, 0L, 0L, "SUCCESS_WITH_ISSUES", 2L,
        new BigDecimal("12.50"), "upstream");
    BiCodingSource source = source(
        List.of(mergeRequest(701L, LocalDate.of(2026, 8, 8), "张三", "草图", 500)),
        List.of(review),
        List.of(),
        false,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("4.00");
    assertThat(response.data().reviewPoints()).isEmpty();
    assertThat(response.data().scanTrend()).isEmpty();
    assertThat(response.data().commentRatePoints()).isEmpty();
    assertThat(response.data().reviewDensityTrend()).isEmpty();
  }

  @Test
  void rejectsConflictingReviewedLinesForTheSameMergeRequest() {
    BiCodingSource source = source(
        List.of(mergeRequest(801L, LocalDate.of(2026, 8, 9), "张三", "草图", 500)),
        List.of(
            review(8L, 801L, LocalDate.of(2026, 8, 9), "草图", 500, "30", 2, 1, 1, 0, 0, 0),
            review(9L, 801L, LocalDate.of(2026, 8, 10), "草图", 600, "30", 2, 1, 1, 0, 0, 0)),
        List.of(),
        false,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isNull();
    assertThat(response.data().moduleReviewQuality()).isEmpty();
    assertThat(response.data().reviewPoints()).hasSize(2);
    assertThat(response.data().reviewDensityTrend()).isEmpty();
  }

  @Test
  void authorConflictDoesNotHideIndependentCodeScaleOrModuleFacts() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(851L, LocalDate.of(2026, 8, 10), "张三", "草图", 500),
            mergeRequest(851L, LocalDate.of(2026, 8, 10), "李四", "草图", 500)),
        List.of(),
        List.of(),
        false,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().addedLines()).isEqualTo(500L);
    assertThat(response.data().codeTrend()).hasSize(1);
    assertThat(response.data().contributors()).isEmpty();
    assertThat(response.data().moduleIncrements()).singleElement().satisfies(module ->
        assertThat(module.addedLines()).isEqualTo(500L));
  }

  @Test
  void moduleConflictDoesNotHideIndependentContributorFacts() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(852L, LocalDate.of(2026, 8, 10), "张三", "草图", 500),
            mergeRequest(852L, LocalDate.of(2026, 8, 10), "张三", "装配", 500)),
        List.of(),
        List.of(),
        false,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().contributors()).singleElement().satisfies(contributor ->
        assertThat(contributor.addedLines()).isEqualTo(500L));
    assertThat(response.data().moduleIncrements()).isEmpty();
  }

  @Test
  void acceptsCommentRateWithoutOptionalSourceExplanation() {
    BiCodingSource.CodeReviewRecord review = new BiCodingSource.CodeReviewRecord(
        10L,
        identity(901L),
        LocalDate.of(2026, 8, 11),
        BiSourceDimension.identified("草图"),
        500L,
        new BigDecimal("30"),
        2L,
        1L,
        1L,
        0L,
        0L,
        0L,
        "SUCCESS_WITH_ISSUES",
        2L,
        new BigDecimal("12.50"),
        null);
    BiCodingSource source = source(
        List.of(mergeRequest(901L, LocalDate.of(2026, 8, 11), "张三", "草图", 500)),
        List.of(review),
        List.of(),
        false,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().commentRatePoints()).singleElement().satisfies(point ->
        assertThat(point.commentRate()).isEqualByComparingTo("12.50"));
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("comment-rate");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
  }

  @Test
  void keepsQualityTrendDensityEmptyWhenReviewRecordsLackMergeRequestIdentity() {
    BiCodingSource.CodeReviewRecord reviewWithoutIdentity = new BiCodingSource.CodeReviewRecord(
        7L,
        null,
        LocalDate.of(2026, 8, 7),
        BiSourceDimension.identified("草图"),
        900L,
        new BigDecimal("90"),
        3L,
        1L,
        1L,
        1L,
        0L,
        0L,
        "SUCCESS_WITH_ISSUES",
        2L,
        new BigDecimal("12.50"),
        null);
    BiCodingSource.CodeReviewRecord anotherReviewWithoutIdentity = new BiCodingSource.CodeReviewRecord(
        10L,
        null,
        LocalDate.of(2026, 8, 7),
        BiSourceDimension.identified("装配"),
        100L,
        new BigDecimal("10"),
        1L,
        0L,
        0L,
        1L,
        0L,
        0L,
        "SUCCESS_WITH_ISSUES",
        1L,
        new BigDecimal("10.00"),
        null);
    BiCodingSource source = source(
        List.of(mergeRequest(701L, LocalDate.of(2026, 8, 7), "张三", "草图", 900)),
        List.of(reviewWithoutIdentity, anotherReviewWithoutIdentity),
        List.of(),
        false,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().reviewDensityTrend()).isEmpty();
    assertThat(response.data().commentRatePoints()).isNotEmpty();
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    });
  }

  @Test
  void calculatesQualityTrendDensityFromPerMergeRequestLinesWhenIdentitiesComplete() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(801L, LocalDate.of(2026, 8, 8), "张三", "草图", 600),
            mergeRequest(802L, LocalDate.of(2026, 8, 8), "李四", "装配", 400)),
        List.of(
            review(8L, 801L, LocalDate.of(2026, 8, 8), "草图", 600, "60", 3, 1, 1, 1, 0, 0),
            review(9L, 802L, LocalDate.of(2026, 8, 8), "装配", 400, "40", 1, 0, 0, 1, 0, 0)),
        List.of(),
        false,
        true,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().reviewDensityTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo("2026-08-08");
      assertThat(point.reviewDefectDensity()).isEqualByComparingTo("4.00");
    });
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
  }

  @Test
  void judgesManualWalkthroughDensityBelowUpdatedLowerBoundAsNotAchieved() {
    BiCodingSource source = source(
        List.of(mergeRequest(301L, LocalDate.of(2026, 8, 10), "张三", "草图", 1200)),
        List.of(review(31L, 301L, LocalDate.of(2026, 8, 10), "草图", 1200, "60", 3, 1, 1, 1, 0, 0)),
        List.of(),
        false,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("2.50");
    assertThat(response.data().summary().reviewDensityAchieved()).isFalse();
  }

  @Test
  void judgesManualWalkthroughDensityBetweenOldAndNewUpperBoundAsAchieved() {
    BiCodingSource source = source(
        List.of(mergeRequest(302L, LocalDate.of(2026, 8, 11), "李四", "装配", 1000)),
        List.of(review(32L, 302L, LocalDate.of(2026, 8, 11), "装配", 1000, "60", 11, 3, 3, 3, 1, 1)),
        List.of(),
        false,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("11.00");
    assertThat(response.data().summary().reviewDensityAchieved()).isTrue();
  }

  @Test
  void judgesManualWalkthroughDensityAboveNewUpperBoundAsNotAchieved() {
    BiCodingSource source = source(
        List.of(mergeRequest(303L, LocalDate.of(2026, 8, 12), "王五", "工程图", 1000)),
        List.of(review(33L, 303L, LocalDate.of(2026, 8, 12), "工程图", 1000, "60", 13, 4, 4, 3, 1, 1)),
        List.of(),
        false,
        false,
        false);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("13.00");
    assertThat(response.data().summary().reviewDensityAchieved()).isFalse();
  }

  private BiCodingSource source(
      List<BiCodingSource.MergeRequestRecord> mergeRequests,
      List<BiCodingSource.CodeReviewRecord> reviews,
      List<BiCodingSource.CommitRecord> commits,
      boolean commitDetails,
      boolean scan,
      boolean comments) {
    return new BiCodingSource(
        "coding-source-1",
        "coding-snapshot-1",
        BiCodingSource.Granularity.DAY,
        mergeRequests,
        commits,
        reviews,
        true,
        true,
        commitDetails,
        true,
        scan,
        comments);
  }

  private BiCodingSource.MergeRequestRecord mergeRequest(
      long id,
      LocalDate date,
      String authorName,
      String moduleName,
      long addedLines) {
    return new BiCodingSource.MergeRequestRecord(
        identity(id),
        date,
        "repo-1",
        "crowncad",
        BiSourceDimension.fromNullable(authorName, "未标注作者"),
        BiSourceDimension.fromNullable(moduleName, "未标注模块"),
        addedLines);
  }

  private BiCodingSource.CodeReviewRecord review(
      long id,
      long mergeRequestId,
      LocalDate date,
      String moduleName,
      long lines,
      String minutes,
      long problems,
      long code,
      long logic,
      long performance,
      long design,
      long other) {
    return new BiCodingSource.CodeReviewRecord(
        id,
        identity(mergeRequestId),
        date,
        BiSourceDimension.fromNullable(moduleName, "未标注模块"),
        lines,
        new BigDecimal(minutes),
        problems,
        code,
        logic,
        performance,
        design,
        other,
        "SUCCESS_WITH_ISSUES",
        2L,
        new BigDecimal("12.50"),
        "upstream");
  }

  private BiCodingSource.MergeRequestIdentity identity(long mergeRequestId) {
    return new BiCodingSource.FormalMergeRequestIdentity(1L, mergeRequestId);
  }
}
