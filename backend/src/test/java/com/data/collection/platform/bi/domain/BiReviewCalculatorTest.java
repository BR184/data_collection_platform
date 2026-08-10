package com.data.collection.platform.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.source.BiReviewSource;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiReviewCalculatorTest {
  private final BiReviewCalculator calculator = new BiReviewCalculator();

  @Test
  void returnsEveryFrontendSectionContractWhenReviewSourceIsEmpty() {
    var response = calculator.calculate(
        "requirements", new BiReviewSource("review-source-empty", "review-snapshot-empty", List.of()));

    assertThat(response.status()).isEqualTo(BiDataStatus.EMPTY);
    assertThat(response.sections()).extracting("key")
        .contains("problem-categories", "module-quality", "review-scatter");
  }

  @Test
  void calculatesConfirmedReviewDensityRateCategoriesAndTargets() {
    BiReviewSource source = new BiReviewSource(
        "review-source-7",
        "review-snapshot-7",
        List.of(
            record(1L, "草图", 10, "4.0", 3, 1, 1, 1, 0),
            record(2L, "草图", 5, "1.0", 1, 0, 1, 0, 0)));

    var response = calculator.calculate("requirements", source);

    assertThat(response.status()).isEqualTo(BiDataStatus.READY);
    assertThat(response.data().summary().defectDensity()).isEqualByComparingTo("0.27");
    assertThat(response.data().summary().reviewRate()).isEqualByComparingTo("3.00");
    assertThat(response.data().summary().achieved()).isTrue();
    assertThat(response.data().categories()).extracting("category", "count")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("文档规范", 1L),
            org.assertj.core.groups.Tuple.tuple("完整性", 2L),
            org.assertj.core.groups.Tuple.tuple("功能性", 1L),
            org.assertj.core.groups.Tuple.tuple("可行性", 0L));
    assertThat(response.data().modules()).hasSize(1);
  }

  @Test
  void groupsModulesByAvailableSourceNamesWithoutSyntheticIds() {
    BiReviewSource source = new BiReviewSource(
        "review-source-8",
        "review-snapshot-8",
        List.of(record(3L, "装配", 8, "2.0", 2, 1, 1, 0, 0)));

    var response = calculator.calculate("design", source);

    assertThat(response.status()).isEqualTo(BiDataStatus.READY);
    assertThat(response.data().modules()).extracting(
        item -> item.module().displayName(), item -> item.reviewedPages())
        .containsExactly(org.assertj.core.groups.Tuple.tuple("装配", 8L));
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("module-quality");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
  }

  @Test
  void acceptsNegativeCompatibilityReviewIdAsStableNonZeroIdentity() {
    BiReviewSource.ReviewRecord compatibilityRecord = new BiReviewSource.ReviewRecord(
        -3L,
        LocalDate.of(2026, 8, 3),
        BiSourceDimension.identified("装配"),
        8L,
        new BigDecimal("2.0"),
        2,
        1,
        1,
        0,
        0);

    var response = calculator.calculate(
        "design",
        new BiReviewSource(
            "review-source-compatibility",
            "review-snapshot-compatibility",
            List.of(compatibilityRecord)));

    assertThat(response.status()).isEqualTo(BiDataStatus.READY);
    assertThat(response.data().summary().reviewCount()).isEqualTo(1L);
    assertThat(response.data().reviewPoints()).extracting("reviewId").containsExactly(-3L);
  }

  @Test
  void rejectsZeroReviewIdBecauseItIsNotAStableSourceIdentity() {
    BiReviewSource.ReviewRecord zeroIdRecord = new BiReviewSource.ReviewRecord(
        0L,
        LocalDate.of(2026, 8, 3),
        BiSourceDimension.identified("装配"),
        8L,
        new BigDecimal("2.0"),
        2,
        1,
        1,
        0,
        0);

    var response = calculator.calculate(
        "design",
        new BiReviewSource(
            "review-source-zero-id",
            "review-snapshot-zero-id",
            List.of(zeroIdRecord)));

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data()).isNull();
  }

  @Test
  void exposesVerifiedReviewPhysicalFieldsInTrace() {
    var response = calculator.calculate(
        "requirements",
        new BiReviewSource(
            "review-source-trace",
            "review-snapshot-trace",
            List.of(record(14L, "草图", 10, "2", 2, 1, 1, 0, 0))));

    assertThat(response.traces()).flatExtracting(trace -> trace.sourceFields())
        .extracting("englishName")
        .contains(
            "review_visible_records.id",
            "review_visible_records.module_name",
            "review_visible_problem_items.workload_hours")
        .doesNotContain("moduleId", "reviewScalePages");
  }

  @Test
  void keepsZeroDenominatorsUncomputableInsteadOfReturningZero() {
    BiReviewSource source = new BiReviewSource(
        "review-source-9",
        "review-snapshot-9",
        List.of(record(4L, "工程图", 0, "0", 2, 1, 1, 0, 0)));

    var response = calculator.calculate("requirements", source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().defectDensity()).isNull();
    assertThat(response.data().summary().reviewRate()).isNull();
    assertThat(response.data().summary().achieved()).isNull();
  }

  @Test
  void rejectsConflictingReviewIdsInsteadOfUsingTheFirstRecord() {
    BiReviewSource source = new BiReviewSource(
        "review-source-10",
        "review-snapshot-10",
        List.of(
            record(10L, "草图", 10, "2", 2, 1, 1, 0, 0),
            record(10L, "草图", 20, "2", 2, 1, 1, 0, 0)));

    var response = calculator.calculate("requirements", source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data()).isNull();
    assertThat(response.sections()).extracting("key")
        .containsExactly(
            "source-consistency", "overview", "problem-categories", "module-quality", "review-scatter");
    assertThat(response.sections()).allSatisfy(section ->
        assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE));
  }

  @Test
  void rejectsNegativeMetricsInsteadOfClampingThemToZero() {
    BiReviewSource source = new BiReviewSource(
        "review-source-11",
        "review-snapshot-11",
        List.of(record(11L, "草图", -3, "2", 2, 1, 1, 0, 0)));

    var response = calculator.calculate("requirements", source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().reviewedPages()).isNull();
    assertThat(response.data().summary().effectiveProblemCount()).isEqualTo(2L);
    assertThat(response.data().categories()).hasSize(4);
    assertThat(response.data().modules()).isEmpty();
    assertThat(response.data().reviewPoints()).isEmpty();
  }

  @Test
  void omitsScatterWhenReviewDateIsMissingWithoutHidingIndependentTotals() {
    BiReviewSource.ReviewRecord record = new BiReviewSource.ReviewRecord(
        12L, null, BiSourceDimension.identified("草图"),
        10L, new BigDecimal("2"), 2, 1, 1, 0, 0);
    BiReviewSource source = new BiReviewSource(
        "review-source-12", "review-snapshot-12", List.of(record));

    var response = calculator.calculate("requirements", source);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().reviewedPages()).isEqualTo(10L);
    assertThat(response.data().reviewPoints()).isEmpty();
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("review-scatter");
      assertThat(section.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    });
  }

  @Test
  void keepsIndependentProblemTotalsWhenReviewWorkloadIsMissing() {
    BiReviewSource.ReviewRecord record = new BiReviewSource.ReviewRecord(
        15L,
        LocalDate.of(2026, 8, 15),
        BiSourceDimension.identified("草图"),
        0L,
        null,
        2,
        1,
        1,
        0,
        0);

    var response = calculator.calculate(
        "design",
        new BiReviewSource("review-source-15", "review-snapshot-15", List.of(record)));

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().summary().reviewCount()).isEqualTo(1L);
    assertThat(response.data().summary().reviewedPages()).isZero();
    assertThat(response.data().summary().workloadHours()).isNull();
    assertThat(response.data().summary().effectiveProblemCount()).isEqualTo(2L);
    assertThat(response.data().categories()).hasSize(4);
    assertThat(response.sections()).anySatisfy(section -> {
      assertThat(section.key()).isEqualTo("problem-categories");
      assertThat(section.status()).isEqualTo(BiDataStatus.READY);
    });
  }

  @Test
  void deduplicatesExactReviewRecordsByStableId() {
    BiReviewSource.ReviewRecord record = record(
        13L, "草图", 10, "2", 2, 1, 1, 0, 0);
    BiReviewSource source = new BiReviewSource(
        "review-source-13", "review-snapshot-13", List.of(record, record));

    var response = calculator.calculate("requirements", source);

    assertThat(response.data().summary().reviewCount()).isEqualTo(1L);
    assertThat(response.data().summary().reviewedPages()).isEqualTo(10L);
  }

  private BiReviewSource.ReviewRecord record(
      long id,
      String moduleName,
      long pages,
      String workload,
      long effectiveProblems,
      long documentSpecification,
      long integrity,
      long functionality,
      long feasibility) {
    return new BiReviewSource.ReviewRecord(
        id,
        LocalDate.of(2026, 8, (int) id),
        BiSourceDimension.fromNullable(moduleName, "未标注模块"),
        pages,
        new BigDecimal(workload),
        effectiveProblems,
        documentSpecification,
        integrity,
        functionality,
        feasibility);
  }
}
