package com.data.collection.platform.bi.domain;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiMetricTrace;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiPageSection;
import com.data.collection.platform.bi.domain.model.BiReviewPageData;
import com.data.collection.platform.bi.domain.source.BiReviewSource;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 严格实现 RQ/DS 人工确认口径的需求和设计评审计算器。 */
public final class BiReviewCalculator {
  private static final BigDecimal MIN_DENSITY = new BigDecimal("0.20");
  private static final BigDecimal MAX_DENSITY = new BigDecimal("0.60");
  private static final BigDecimal DESIGN_MIN_DENSITY = new BigDecimal("0.30");
  private static final BigDecimal DESIGN_MAX_DENSITY = new BigDecimal("0.80");
  private static final String RULE_VERSION = "bi-review-v3";

  /** 计算需求或设计页面；冲突、缺失和非法输入不会进入任何标记为完整的统计。 */
  public BiPageResponse<BiReviewPageData> calculate(String pageKey, BiReviewSource source) {
    Objects.requireNonNull(source, "source");
    BigDecimal minimumDensity = densityMinimum(pageKey);
    BigDecimal maximumDensity = densityMaximum(pageKey);
    // 阶段一：空范围单独返回 EMPTY，避免把“没有数据”误报为计算失败。
    if (source.records().isEmpty()) {
      return BiPageResponse.create(
          pageKey,
          BiDataStatus.EMPTY,
          source.sourceVersion(),
          source.snapshotId(),
          RULE_VERSION,
          emptySections(),
          traces(pageKey),
          new BiReviewPageData(
              emptySummary(), List.of(), List.of(), List.of(), coverage(0, 0), coverage(0, 0)));
    }

    // 阶段二：按稳定评审 ID 去重，并拒绝同一 ID 对应多个事实的冲突来源。
    DistinctReviews distinct = distinct(source.records());
    boolean sourceConsistent = !distinct.conflict()
        && distinct.records().stream().allMatch(record -> record.reviewId() != 0);
    if (!sourceConsistent) {
      return incompleteSource(pageKey, source, "来源中存在零值评审 ID 或同一评审 ID 的冲突记录");
    }

    // 阶段三：先校验各公式所需的分子、分母和分类守恒关系，再决定哪些分区可计算。
    List<BiReviewSource.ReviewRecord> records = distinct.records();
    boolean reviewedPagesComplete = records.stream().allMatch(this::validReviewedPages);
    boolean workloadComplete = records.stream().allMatch(this::validWorkload);
    boolean problemTotalsComplete = records.stream().allMatch(this::validProblemTotal);
    Long reviewedPages = reviewedPagesComplete
        ? records.stream().mapToLong(BiReviewSource.ReviewRecord::reviewedPages).sum()
        : null;
    BigDecimal workloadHours = workloadComplete
        ? records.stream().map(BiReviewSource.ReviewRecord::workloadHours)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
        : null;
    Long problemCount = problemTotalsComplete
        ? records.stream().mapToLong(BiReviewSource.ReviewRecord::effectiveProblemCount).sum()
        : null;
    BigDecimal density = reviewedPages == null || problemCount == null
        ? null : divide(problemCount, reviewedPages);
    BigDecimal rate = reviewedPages == null
        ? null : divide(reviewedPages, workloadHours);
    Boolean achieved = achieved(density, minimumDensity, maximumDensity);
    boolean denominatorComplete = density != null && rate != null;
    boolean categoriesComplete = records.stream().allMatch(this::validCategoryInputs);
    List<BiReviewSource.ReviewRecord> moduleRecords = records.stream()
        .filter(this::validModuleInputs)
        .toList();
    List<BiReviewSource.ReviewRecord> scatterRecords = records.stream()
        .filter(this::validScatterInputs)
        .toList();
    boolean moduleComplete = moduleRecords.size() == records.size();
    boolean scatterComplete = scatterRecords.size() == records.size();

    // 阶段四：按分区独立组装结果；某一指标缺失只影响对应分区，不污染其他可用数据。
    List<BiPageSection> sections = List.of(
        new BiPageSection("source-consistency", "来源一致性", BiDataStatus.READY, ""),
        new BiPageSection(
            "overview",
            "整体评审质量",
            denominatorComplete ? BiDataStatus.READY : BiDataStatus.INCOMPLETE,
            denominatorComplete ? "" : "被评审页数或独立评审工作量不足，相关指标不可计算"),
        new BiPageSection(
            "problem-categories",
            "评审问题类别",
            categoriesComplete ? BiDataStatus.READY : BiDataStatus.INCOMPLETE,
            categoriesComplete ? "" : "有效问题类别计数与有效问题总数不一致"),
        new BiPageSection(
            "module-quality",
            "模块评审质量",
            coverageStatus(records.size(), moduleRecords.size()),
            coverageMessage(records.size(), moduleRecords.size(), "模块、页数或独立评审工作量")),
        new BiPageSection(
            "review-scatter",
            "单次评审质量分布",
            coverageStatus(records.size(), scatterRecords.size()),
            coverageMessage(records.size(), scatterRecords.size(), "日期、页数或独立评审工作量")));
    BiDataStatus status = denominatorComplete && categoriesComplete && moduleComplete && scatterComplete
        ? BiDataStatus.READY : BiDataStatus.INCOMPLETE;
    return BiPageResponse.create(
        pageKey,
        status,
        source.sourceVersion(),
        source.snapshotId(),
        RULE_VERSION,
        sections,
        traces(pageKey),
        new BiReviewPageData(
            new BiReviewPageData.Summary(
                records.size(),
                reviewedPages,
                workloadHours,
                problemCount,
                density,
                rate,
                achieved),
            categoriesComplete ? categories(records) : List.of(),
            modules(moduleRecords, minimumDensity, maximumDensity),
            points(scatterRecords, minimumDensity, maximumDensity),
            coverage(records.size(), moduleRecords.size()),
            coverage(records.size(), scatterRecords.size())));
  }

  private BiReviewPageData.Summary emptySummary() {
    return new BiReviewPageData.Summary(0, 0L, BigDecimal.ZERO, 0L, null, null, null);
  }

  private List<BiPageSection> emptySections() {
    String message = "当前产品版本没有本阶段评审记录";
    return List.of(
        new BiPageSection("source-consistency", "来源一致性", BiDataStatus.READY, ""),
        new BiPageSection("overview", "整体评审质量", BiDataStatus.EMPTY, message),
        new BiPageSection("problem-categories", "评审问题类别", BiDataStatus.EMPTY, message),
        new BiPageSection("module-quality", "模块评审质量", BiDataStatus.EMPTY, message),
        new BiPageSection("review-scatter", "单次评审质量分布", BiDataStatus.EMPTY, message));
  }

  private BiPageResponse<BiReviewPageData> incompleteSource(
      String pageKey,
      BiReviewSource source,
      String message) {
    return BiPageResponse.create(
        pageKey,
        BiDataStatus.INCOMPLETE,
        source.sourceVersion(),
        source.snapshotId(),
        RULE_VERSION,
        incompleteSections(message),
        traces(pageKey),
        null);
  }

  private List<BiPageSection> incompleteSections(String sourceMessage) {
    String message = "来源一致性不足，无法计算该区块";
    return List.of(
        new BiPageSection("source-consistency", "来源一致性", BiDataStatus.INCOMPLETE, sourceMessage),
        new BiPageSection("overview", "整体评审质量", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("problem-categories", "评审问题类别", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("module-quality", "模块评审质量", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("review-scatter", "单次评审质量分布", BiDataStatus.INCOMPLETE, message));
  }

  private List<BiReviewPageData.CategoryBreakdown> categories(
      List<BiReviewSource.ReviewRecord> records) {
    CategoryTotals totals = new CategoryTotals();
    records.forEach(totals::add);
    return List.of(
        category("文档规范", totals.documentSpecification, totals.problemCount),
        category("完整性", totals.integrity, totals.problemCount),
        category("功能性", totals.functionality, totals.problemCount),
        category("可行性", totals.feasibility, totals.problemCount));
  }

  private BiReviewPageData.CategoryBreakdown category(String name, long count, long total) {
    return new BiReviewPageData.CategoryBreakdown(name, count, percent(count, total));
  }

  private List<BiReviewPageData.ModuleQuality> modules(
      List<BiReviewSource.ReviewRecord> records,
      BigDecimal minimumDensity,
      BigDecimal maximumDensity) {
    Map<BiSourceDimension, ModuleAccumulator> modules = new LinkedHashMap<>();
    for (BiReviewSource.ReviewRecord record : records) {
      modules.computeIfAbsent(
              record.module(),
              module -> new ModuleAccumulator(module, minimumDensity, maximumDensity))
          .add(record);
    }
    return modules.values().stream().map(ModuleAccumulator::toData).toList();
  }

  private List<BiReviewPageData.ReviewPoint> points(
      List<BiReviewSource.ReviewRecord> records,
      BigDecimal minimumDensity,
      BigDecimal maximumDensity) {
    List<BiReviewPageData.ReviewPoint> points = new ArrayList<>();
    for (BiReviewSource.ReviewRecord record : records) {
      BigDecimal rate = divide(record.reviewedPages(), record.workloadHours());
      BigDecimal density = divide(record.effectiveProblemCount(), record.reviewedPages());
      points.add(new BiReviewPageData.ReviewPoint(
          record.reviewId(),
          record.reviewDate(),
          record.module(),
          rate,
          density,
          achieved(density, minimumDensity, maximumDensity)));
    }
    return points;
  }

  private List<BiMetricTrace> traces(String pageKey) {
    String prefix = "design".equals(pageKey) ? "DS" : "RQ";
    return List.of(new BiMetricTrace(
        "DS".equals(prefix)
            ? List.of("DS-01", "DS-01A", "DS-01B", "DS-02", "DS-03", "DS-04", "DS-05",
                "DS-06", "DS-07", "DS-08", "DS-09", "DS-10", "DS-11", "DS-12", "DS-13",
                "DS-14", "DS-15", "DS-16", "DS-17", "DS-18", "DS-19", "DS-20", "DS-21", "DS-22")
            : List.of("RQ-01", "RQ-02", "RQ-03", "RQ-04", "RQ-05", "RQ-06", "RQ-07",
                "RQ-08", "RQ-09", "RQ-10", "RQ-11", "RQ-12", "RQ-13", "RQ-14", "RQ-15",
                "RQ-16", "RQ-17", "RQ-18", "RQ-19", "RQ-20", "RQ-21", "RQ-22", "RQ-23", "RQ-24"),
        "数据采集平台",
        List.of(
            new BiMetricTrace.SourceField("评审记录 ID", "review_visible_records.id"),
            new BiMetricTrace.SourceField("评审日期", "review_visible_records.review_date"),
            new BiMetricTrace.SourceField("模块名称", "review_visible_records.module_name"),
            new BiMetricTrace.SourceField("被评审页数", "review_visible_records.review_scale_pages"),
            new BiMetricTrace.SourceField("独立评审实际工作量（小时）", "review_visible_problem_items.workload_hours"),
            new BiMetricTrace.SourceField("问题状态", "review_visible_problem_items.problem_status"),
            new BiMetricTrace.SourceField("问题类别", "review_visible_problem_items.problem_category")),
        "有效问题数 / 被评审页数；被评审页数 / 独立评审工作量；密度区间 ["
            + densityMinimum(pageKey) + ", " + densityMaximum(pageKey) + "] 达标",
        "BI服务端"));
  }

  private BigDecimal divide(long numerator, long denominator) {
    return denominator <= 0 ? null : BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }

  private BigDecimal divide(long numerator, BigDecimal denominator) {
    return denominator == null || denominator.signum() <= 0 ? null : BigDecimal.valueOf(numerator)
        .divide(denominator, 2, RoundingMode.HALF_UP);
  }

  private BigDecimal percent(long numerator, long denominator) {
    return denominator <= 0 ? null : BigDecimal.valueOf(numerator * 100L)
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }

  /** 设计页与需求页的人工确认目标区间不同，按页面键选择缺陷密度达标下限。 */
  private static BigDecimal densityMinimum(String pageKey) {
    return "design".equals(pageKey) ? DESIGN_MIN_DENSITY : MIN_DENSITY;
  }

  /** 设计页与需求页的人工确认目标区间不同，按页面键选择缺陷密度达标上限。 */
  private static BigDecimal densityMaximum(String pageKey) {
    return "design".equals(pageKey) ? DESIGN_MAX_DENSITY : MAX_DENSITY;
  }

  private Boolean achieved(
      BigDecimal density, BigDecimal minimumDensity, BigDecimal maximumDensity) {
    return density == null ? null
        : density.compareTo(minimumDensity) >= 0 && density.compareTo(maximumDensity) <= 0;
  }

  private boolean validReviewedPages(BiReviewSource.ReviewRecord record) {
    return record.reviewedPages() != null && record.reviewedPages() >= 0;
  }

  private boolean validWorkload(BiReviewSource.ReviewRecord record) {
    return record.workloadHours() != null && record.workloadHours().signum() >= 0;
  }

  private boolean validProblemTotal(BiReviewSource.ReviewRecord record) {
    return record.effectiveProblemCount() >= 0;
  }

  private boolean validCategoryInputs(BiReviewSource.ReviewRecord record) {
    if (record.effectiveProblemCount() < 0
        || record.documentSpecificationCount() < 0
        || record.integrityCount() < 0
        || record.functionalityCount() < 0
        || record.feasibilityCount() < 0) {
      return false;
    }
    return record.effectiveProblemCount()
        == record.documentSpecificationCount()
            + record.integrityCount()
            + record.functionalityCount()
            + record.feasibilityCount();
  }

  private boolean validScatterInputs(BiReviewSource.ReviewRecord record) {
    return record.reviewDate() != null
        && record.reviewedPages() != null && record.reviewedPages() > 0
        && record.workloadHours() != null && record.workloadHours().signum() > 0
        && record.effectiveProblemCount() >= 0;
  }

  private boolean validModuleInputs(BiReviewSource.ReviewRecord record) {
    return record.module() != null
        && validReviewedPages(record)
        && validWorkload(record)
        && validProblemTotal(record)
        && record.reviewedPages() > 0
        && record.workloadHours().signum() > 0;
  }

  private BiReviewPageData.Coverage coverage(long total, long valid) {
    return new BiReviewPageData.Coverage(total, valid, percent(valid, total));
  }

  private BiDataStatus coverageStatus(long total, long valid) {
    if (total == 0) {
      return BiDataStatus.EMPTY;
    }
    if (valid == 0) {
      return BiDataStatus.INCOMPLETE;
    }
    return valid == total ? BiDataStatus.READY : BiDataStatus.INCOMPLETE;
  }

  private String coverageMessage(long total, long valid, String inputs) {
    return valid == total ? "" : "有效观测 " + valid + "/" + total + "；其余记录缺少" + inputs;
  }

  private DistinctReviews distinct(List<BiReviewSource.ReviewRecord> records) {
    Map<Long, BiReviewSource.ReviewRecord> values = new LinkedHashMap<>();
    boolean conflict = false;
    for (BiReviewSource.ReviewRecord record : records) {
      BiReviewSource.ReviewRecord previous = values.putIfAbsent(record.reviewId(), record);
      conflict |= previous != null && !previous.equals(record);
    }
    return new DistinctReviews(List.copyOf(values.values()), conflict);
  }

  private final class ModuleAccumulator {
    private final BiSourceDimension module;
    private final BigDecimal minimumDensity;
    private final BigDecimal maximumDensity;
    private final Totals totals = new Totals();

    private ModuleAccumulator(
        BiSourceDimension module, BigDecimal minimumDensity, BigDecimal maximumDensity) {
      this.module = module;
      this.minimumDensity = minimumDensity;
      this.maximumDensity = maximumDensity;
    }

    private void add(BiReviewSource.ReviewRecord record) {
      totals.add(record);
    }

    private BiReviewPageData.ModuleQuality toData() {
      BigDecimal density = divide(totals.problemCount, totals.reviewedPages);
      return new BiReviewPageData.ModuleQuality(
          module,
          totals.reviewedPages,
          totals.workloadHours,
          totals.problemCount,
          density,
          divide(totals.reviewedPages, totals.workloadHours),
          achieved(density, minimumDensity, maximumDensity));
    }
  }

  private static final class Totals {
    private long reviewedPages;
    private BigDecimal workloadHours = BigDecimal.ZERO;
    private long problemCount;

    private void add(BiReviewSource.ReviewRecord record) {
      reviewedPages += record.reviewedPages();
      workloadHours = workloadHours.add(record.workloadHours());
      problemCount += record.effectiveProblemCount();
    }
  }

  private static final class CategoryTotals {
    private long problemCount;
    private long documentSpecification;
    private long integrity;
    private long functionality;
    private long feasibility;

    private void add(BiReviewSource.ReviewRecord record) {
      problemCount += record.effectiveProblemCount();
      documentSpecification += record.documentSpecificationCount();
      integrity += record.integrityCount();
      functionality += record.functionalityCount();
      feasibility += record.feasibilityCount();
    }
  }

  private record DistinctReviews(
      List<BiReviewSource.ReviewRecord> records,
      boolean conflict) {}
}
