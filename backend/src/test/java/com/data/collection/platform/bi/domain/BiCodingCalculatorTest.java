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
    var response = calculator.calculate(source(List.of(), List.of(), List.of(), false, false));

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
        2L, 1L, 1L, 0L, 0L, 0L,
        new BigDecimal("12.50"), "upstream");
    BiCodingSource source = source(
        List.of(mergeRequest(701L, LocalDate.of(2026, 8, 8), "张三", "草图", 500)),
        List.of(review),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("4.00");
    assertThat(response.data().reviewPoints()).isEmpty();
    assertThat(response.data().commentRateTrend()).isEmpty();
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
        new BigDecimal("12.50"),
        null);
    BiCodingSource source = source(
        List.of(mergeRequest(901L, LocalDate.of(2026, 8, 11), "张三", "草图", 500)),
        List.of(review),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo(LocalDate.of(2026, 8, 11));
      assertThat(point.averageCommentRate()).isEqualByComparingTo("12.50");
    });
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
        new BigDecimal("10.00"),
        null);
    BiCodingSource source = source(
        List.of(mergeRequest(701L, LocalDate.of(2026, 8, 7), "张三", "草图", 900)),
        List.of(reviewWithoutIdentity, anotherReviewWithoutIdentity),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().reviewDensityTrend()).isEmpty();
    // 密度因缺少稳定 MR 身份为空，但注释率仍按同一时间桶聚合出数：12.50 与 10.00 的平均为 11.25。
    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo(LocalDate.of(2026, 8, 7));
      assertThat(point.averageCommentRate()).isEqualByComparingTo("11.25");
    });
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
        true);

    var response = calculator.calculate(source);

    assertThat(response.data().reviewDensityTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo("2026-08-08");
      assertThat(point.reviewDefectDensity()).isEqualByComparingTo("4.00");
    });
    assertThat(response.data().reviewDensityCoverage().totalObservations()).isEqualTo(2L);
    assertThat(response.data().reviewDensityCoverage().validObservations()).isEqualTo(2L);
    assertThat(response.data().reviewDensityCoverage().coveragePercent()).isEqualByComparingTo("100.00");
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
      assertThat(section.message()).isEmpty();
    });
  }

  @Test
  void rendersDensityTrendFromValidSubsetWhenSomeReviewRowsAreBad() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(901L, LocalDate.of(2026, 8, 8), "张三", "草图", 1000),
            mergeRequest(902L, LocalDate.of(2026, 8, 8), "李四", "装配", 0)),
        List.of(
            review(21L, 901L, LocalDate.of(2026, 8, 8), "草图", 1000, "60", 4, 1, 1, 1, 1, 0),
            review(22L, 902L, LocalDate.of(2026, 8, 8), "装配", 0, "0", 9, 0, 0, 0, 0, 0)),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 坏行（被走查行数为 0）只剔除自身：密度曲线仍基于合法子集出数，且其缺陷不计入分子。
    assertThat(response.data().reviewDensityTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo("2026-08-08");
      assertThat(point.reviewDefectDensity()).isEqualByComparingTo("4.00");
    });
    assertThat(response.data().reviewDensityCoverage().totalObservations()).isEqualTo(2L);
    assertThat(response.data().reviewDensityCoverage().validObservations()).isEqualTo(1L);
    assertThat(response.data().reviewDensityCoverage().coveragePercent()).isEqualByComparingTo("50.00");
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
      assertThat(section.message()).contains("注释率有效走查记录 2/2")
          .contains("密度有效走查记录 1/2");
    });
  }

  @Test
  void rendersDensityTrendExcludingOnlyConflictingMergeRequestRecords() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(911L, LocalDate.of(2026, 8, 8), "张三", "草图", 1000),
            mergeRequest(912L, LocalDate.of(2026, 8, 8), "李四", "装配", 500)),
        List.of(
            review(30L, 911L, LocalDate.of(2026, 8, 8), "草图", 1000, "60", 5, 2, 1, 1, 1, 0),
            review(31L, 912L, LocalDate.of(2026, 8, 8), "装配", 500, "30", 2, 1, 1, 0, 0, 0),
            review(32L, 912L, LocalDate.of(2026, 8, 9), "装配", 600, "30", 2, 1, 1, 0, 0, 0)),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 同 MR 行数冲突只剔除冲突 MR 的走查记录，其余合法记录仍参与密度趋势。
    assertThat(response.data().reviewDensityTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo("2026-08-08");
      assertThat(point.reviewDefectDensity()).isEqualByComparingTo("5.00");
    });
    assertThat(response.data().reviewDensityCoverage().totalObservations()).isEqualTo(3L);
    assertThat(response.data().reviewDensityCoverage().validObservations()).isEqualTo(1L);
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
      assertThat(section.message()).contains("注释率有效走查记录 3/3")
          .contains("密度有效走查记录 1/3");
    });
  }

  @Test
  void includesDensityTrendObservationsWithoutReviewDuration() {
    BiCodingSource.CodeReviewRecord noDuration = new BiCodingSource.CodeReviewRecord(
        51L, identity(921L), LocalDate.of(2026, 8, 8), BiSourceDimension.identified("草图"),
        1000L, null, 3L, 1L, 1L, 1L, 0L, 0L,
        new BigDecimal("12.50"), "upstream");
    BiCodingSource source = source(
        List.of(mergeRequest(921L, LocalDate.of(2026, 8, 8), "张三", "草图", 1000)),
        List.of(noDuration),
        List.of(),
        false,
        true,
        BiCodingSource.Granularity.DAY,
        false,
        true);

    var response = calculator.calculate(source);

    // 密度趋势不依赖工时；工时缺失的合法记录仍应出数而不是被丢弃。
    assertThat(response.data().reviewDensityTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo("2026-08-08");
      assertThat(point.reviewDefectDensity()).isEqualByComparingTo("3.00");
    });
    assertThat(response.data().reviewDensityCoverage().coveragePercent())
        .isEqualByComparingTo("100.00");
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
  }

  @Test
  void excludesConflictingDensityFactsRegardlessOfInputOrder() {
    BiCodingSource.CodeReviewRecord first = review(
        61L, 931L, LocalDate.of(2026, 8, 9), "草图", 500, "30", 2,
        1, 1, 0, 0, 0, new BigDecimal("20"), "upstream");
    BiCodingSource.CodeReviewRecord conflicting = review(
        61L, 931L, LocalDate.of(2026, 8, 9), "草图", 500, "30", 3,
        1, 1, 1, 0, 0, new BigDecimal("20"), "upstream");

    var forward = calculator.calculate(source(
        List.of(mergeRequest(931L, LocalDate.of(2026, 8, 9), "张三", "草图", 500)),
        List.of(first, conflicting), List.of(), false, true));
    var reversed = calculator.calculate(source(
        List.of(mergeRequest(931L, LocalDate.of(2026, 8, 9), "张三", "草图", 500)),
        List.of(conflicting, first), List.of(), false, true));

    // 同一走查 ID 的缺陷数冲突不能把首条记录当作密度事实；交换来源顺序后的结果必须相同且不出点。
    assertThat(forward.data().reviewDensityTrend()).isEmpty();
    assertThat(reversed.data().reviewDensityTrend()).isEmpty();
    assertThat(forward.data().reviewDensityCoverage().totalObservations()).isEqualTo(1L);
    assertThat(forward.data().reviewDensityCoverage().validObservations()).isEqualTo(0L);
    assertThat(forward.data().reviewDensityCoverage().coveragePercent())
        .isEqualByComparingTo("0.00");
    assertThat(reversed.data().reviewDensityCoverage()).isEqualTo(
        forward.data().reviewDensityCoverage());
    assertThat(forward.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
      assertThat(section.message()).contains("密度有效走查记录 0/1");
    });
  }

  @Test
  void judgesManualWalkthroughDensityBelowUpdatedLowerBoundAsNotAchieved() {
    BiCodingSource source = source(
        List.of(mergeRequest(301L, LocalDate.of(2026, 8, 10), "张三", "草图", 1200)),
        List.of(review(31L, 301L, LocalDate.of(2026, 8, 10), "草图", 1200, "60", 3, 1, 1, 1, 0, 0)),
        List.of(),
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
        false);

    var response = calculator.calculate(source);

    assertThat(response.data().summary().reviewDefectDensity()).isEqualByComparingTo("13.00");
    assertThat(response.data().summary().reviewDensityAchieved()).isFalse();
  }

  @Test
  void averagesCommentRatesWithinTheSameDayInsteadOfKeepingTheLastRecord() {
    BiCodingSource source = source(
        List.of(mergeRequest(1001L, LocalDate.of(2025, 11, 17), "张三", "草图", 500)),
        List.of(
            review(1001L, 1001L, LocalDate.of(2025, 11, 17), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("0"), "upstream"),
            review(1002L, 1001L, LocalDate.of(2025, 11, 17), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("10"), "upstream"),
            review(1003L, 1001L, LocalDate.of(2025, 11, 17), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("50"), "upstream"),
            review(1004L, 1001L, LocalDate.of(2025, 11, 17), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("100"), "upstream")),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 同日 0、10、50、100 的非加权算术平均为 40.00，绝不返回输入顺序中的最后一条 100。
    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo(LocalDate.of(2025, 11, 17));
      assertThat(point.averageCommentRate()).isEqualByComparingTo("40.00");
    });
  }

  @Test
  void ignoresNullNegativeAndUndatedCommentRatesWhileReportingRecordCoverage() {
    BiCodingSource.CodeReviewRecord undatedReview = new BiCodingSource.CodeReviewRecord(
        1104L, identity(1101L), null, BiSourceDimension.identified("草图"), 500L,
        new BigDecimal("30"), 2L, 1L, 1L, 0L, 0L, 0L, new BigDecimal("80"), "upstream");
    BiCodingSource source = source(
        List.of(mergeRequest(1101L, LocalDate.of(2026, 8, 20), "张三", "草图", 500)),
        List.of(
            review(1101L, 1101L, LocalDate.of(2026, 8, 20), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("10"), "upstream"),
            review(1102L, 1101L, LocalDate.of(2026, 8, 20), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("-5"), "upstream"),
            review(1103L, 1101L, LocalDate.of(2026, 8, 20), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                null, "upstream"),
            undatedReview),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 只有 10 是合法观测：NULL、负值与缺失日期都不进入平均。
    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo(LocalDate.of(2026, 8, 20));
      assertThat(point.averageCommentRate()).isEqualByComparingTo("10.00");
    });
    // 覆盖率仍按走查记录数统计，而不是趋势周期数。
    assertThat(response.data().commentRateCoverage().totalObservations()).isEqualTo(4L);
    assertThat(response.data().commentRateCoverage().validObservations()).isEqualTo(1L);
    assertThat(response.data().commentRateCoverage().coveragePercent()).isEqualByComparingTo("25.00");
  }

  @Test
  void bucketsCommentRateByWeekStartingOnMondayWhenWeekGranularityIsRequested() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(1201L, LocalDate.of(2026, 8, 19), "张三", "草图", 500),
            mergeRequest(1202L, LocalDate.of(2026, 8, 21), "李四", "装配", 500),
            mergeRequest(1203L, LocalDate.of(2026, 8, 25), "王五", "工程图", 500)),
        List.of(
            review(1201L, 1201L, LocalDate.of(2026, 8, 19), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("10"), "upstream"),
            review(1202L, 1202L, LocalDate.of(2026, 8, 21), "装配", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("20"), "upstream"),
            review(1203L, 1203L, LocalDate.of(2026, 8, 25), "工程图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("60"), "upstream")),
        List.of(),
        false,
        true,
        BiCodingSource.Granularity.WEEK);

    var response = calculator.calculate(source);

    // 08-19（周三）与 08-21（周五）归入 08-17 那一周，平均 (10+20)/2=15.00；08-25（周二）归入 08-24 那一周。
    assertThat(response.data().commentRateTrend()).extracting("period", "averageCommentRate")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 8, 17), new BigDecimal("15.00")),
            org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 8, 24), new BigDecimal("60.00")));
    // 注释率与密度必须使用同一套周期键。
    assertThat(response.data().reviewDensityTrend()).extracting("period")
        .containsExactly(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 24));
  }

  @Test
  void doesNotWeightCommentRateByAddedLinesOrReviewedLines() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(1301L, LocalDate.of(2026, 8, 22), "张三", "草图", 100000),
            mergeRequest(1302L, LocalDate.of(2026, 8, 22), "李四", "装配", 1)),
        List.of(
            review(1301L, 1301L, LocalDate.of(2026, 8, 22), "草图", 100000, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("0"), "upstream"),
            review(1302L, 1302L, LocalDate.of(2026, 8, 22), "装配", 1, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("100"), "upstream")),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 非加权算术平均为 50.00；若按新增行数或被走查行数加权，结果会趋近 0。
    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point ->
        assertThat(point.averageCommentRate()).isEqualByComparingTo("50.00"));
  }

  @Test
  void countsFullyDuplicatedReviewRecordOnlyOnceInCommentRateTrend() {
    BiCodingSource.CodeReviewRecord duplicated = review(1401L, 1401L,
        LocalDate.of(2026, 8, 23), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
        new BigDecimal("20"), "upstream");
    BiCodingSource source = source(
        List.of(mergeRequest(1401L, LocalDate.of(2026, 8, 23), "张三", "草图", 500)),
        List.of(duplicated, duplicated),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 完全相同的走查记录只计一次，平均值仍是 20.00 而不是被重复累加或产生冲突。
    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point ->
        assertThat(point.averageCommentRate()).isEqualByComparingTo("20.00"));
    assertThat(response.data().commentRateCoverage().totalObservations()).isEqualTo(1L);
    assertThat(response.data().commentRateCoverage().validObservations()).isEqualTo(1L);
  }

  @Test
  void ignoresOptionalCommentRateSourceDifferencesWhenDeduplicatingSameRateFact() {
    BiCodingSource.CodeReviewRecord withoutSource = review(
        1451L, 1451L, LocalDate.of(2026, 8, 23), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
        new BigDecimal("20"), null);
    BiCodingSource.CodeReviewRecord withSource = review(
        1451L, 1451L, LocalDate.of(2026, 8, 23), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
        new BigDecimal("20"), "upstream");
    BiCodingSource source = source(
        List.of(mergeRequest(1451L, LocalDate.of(2026, 8, 23), "张三", "草图", 500)),
        List.of(withoutSource, withSource),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 来源说明是可选追溯字段，不能把相同日期/注释率的重复事实判为冲突。
    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point ->
        assertThat(point.averageCommentRate()).isEqualByComparingTo("20.00"));
    assertThat(response.data().commentRateCoverage().totalObservations()).isEqualTo(1L);
    assertThat(response.data().commentRateCoverage().validObservations()).isEqualTo(1L);
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("comment-rate");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
  }

  @Test
  void excludesConflictingCommentRateFactsButKeepsOtherPeriods() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(1501L, LocalDate.of(2026, 8, 24), "张三", "草图", 500),
            mergeRequest(1502L, LocalDate.of(2026, 8, 25), "李四", "装配", 500)),
        List.of(
            review(1501L, 1501L, LocalDate.of(2026, 8, 24), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("10"), "upstream"),
            review(1501L, 1501L, LocalDate.of(2026, 8, 24), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("90"), "upstream"),
            review(1502L, 1502L, LocalDate.of(2026, 8, 25), "装配", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("30"), "upstream")),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 同一走查 ID 的核心字段冲突不得按输入顺序任选首条或末条，整组剔除；
    // 其它无冲突的合法记录仍能形成周期点，注释率区块标记为 INCOMPLETE。
    assertThat(response.data().commentRateTrend()).singleElement().satisfies(point -> {
      assertThat(point.period()).isEqualTo(LocalDate.of(2026, 8, 25));
      assertThat(point.averageCommentRate()).isEqualByComparingTo("30.00");
    });
    assertThat(response.data().commentRateCoverage().totalObservations()).isEqualTo(2L);
    assertThat(response.data().commentRateCoverage().validObservations()).isEqualTo(1L);
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("comment-rate");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
      assertThat(section.message()).contains("有效走查记录 1/2");
    });
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("quality-trend");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
      assertThat(section.message()).contains("注释率有效走查记录 1/2")
          .contains("密度有效走查记录 2/2");
    });
  }

  @Test
  void omitsPeriodsWithoutLegalCommentRateInsteadOfFillingZero() {
    BiCodingSource source = source(
        List.of(
            mergeRequest(1601L, LocalDate.of(2026, 8, 26), "张三", "草图", 500),
            mergeRequest(1602L, LocalDate.of(2026, 8, 27), "李四", "装配", 500)),
        List.of(
            review(1601L, 1601L, LocalDate.of(2026, 8, 26), "草图", 500, "30", 2, 1, 1, 0, 0, 0,
                new BigDecimal("40"), "upstream"),
            review(1602L, 1602L, LocalDate.of(2026, 8, 27), "装配", 500, "30", 2, 1, 1, 0, 0, 0,
                null, "upstream")),
        List.of(),
        false,
        true);

    var response = calculator.calculate(source);

    // 08-27 没有合法注释率观测，注释率轨整体缺位而不是补 0；密度轨仍保留该周期。
    assertThat(response.data().commentRateTrend()).extracting("period")
        .containsExactly(LocalDate.of(2026, 8, 26));
    assertThat(response.data().reviewDensityTrend()).extracting("period")
        .containsExactly(LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 27));
  }

  private BiCodingSource source(
      List<BiCodingSource.MergeRequestRecord> mergeRequests,
      List<BiCodingSource.CodeReviewRecord> reviews,
      List<BiCodingSource.CommitRecord> commits,
      boolean commitDetails,
      boolean comments) {
    return source(mergeRequests, reviews, commits, commitDetails, comments,
        BiCodingSource.Granularity.DAY);
  }

  private BiCodingSource source(
      List<BiCodingSource.MergeRequestRecord> mergeRequests,
      List<BiCodingSource.CodeReviewRecord> reviews,
      List<BiCodingSource.CommitRecord> commits,
      boolean commitDetails,
      boolean comments,
      BiCodingSource.Granularity granularity) {
    return new BiCodingSource(
        "coding-source-1",
        "coding-snapshot-1",
        granularity,
        mergeRequests,
        commits,
        reviews,
        true,
        true,
        commitDetails,
        true,
        comments,
        true);
  }

  private BiCodingSource source(
      List<BiCodingSource.MergeRequestRecord> mergeRequests,
      List<BiCodingSource.CodeReviewRecord> reviews,
      List<BiCodingSource.CommitRecord> commits,
      boolean commitDetails,
      boolean comments,
      BiCodingSource.Granularity granularity,
      boolean reviewMetrics,
      boolean reviewDensity) {
    return new BiCodingSource(
        "coding-source-1",
        "coding-snapshot-1",
        granularity,
        mergeRequests,
        commits,
        reviews,
        true,
        true,
        commitDetails,
        reviewMetrics,
        comments,
        reviewDensity);
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
    return review(id, mergeRequestId, date, moduleName, lines, minutes, problems, code, logic,
        performance, design, other, new BigDecimal("12.50"), "upstream");
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
      long other,
      BigDecimal commentRate,
      String commentRateSource) {
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
        commentRate,
        commentRateSource);
  }

  private BiCodingSource.MergeRequestIdentity identity(long mergeRequestId) {
    return new BiCodingSource.FormalMergeRequestIdentity(1L, mergeRequestId);
  }
}
