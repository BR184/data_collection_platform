package com.data.collection.platform.bi.domain;

import com.data.collection.platform.bi.domain.model.BiCodingPageData;
import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiMetricTrace;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiPageSection;
import com.data.collection.platform.bi.domain.source.BiCodingSource;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 严格实现 CD-01 至 CD-39 人工确认口径的编码阶段计算器。 */
public final class BiCodingCalculator {
  private static final BigDecimal MIN_REVIEW_DENSITY = new BigDecimal("3.00");
  private static final BigDecimal MAX_REVIEW_DENSITY = new BigDecimal("12.00");
  private static final BigDecimal ONE_THOUSAND = new BigDecimal("1000");
  private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal("60");
  private static final String RULE_VERSION = "bi-coding-v3";

  /**
   * 计算编码页面；上游能力、来源维度或计算分母不足时保留可用数据并标记相应分区。
   *
   * @param source 已冻结来源版本的编码事实
   * @return 编码页面强类型响应
   */
  public BiPageResponse<BiCodingPageData> calculate(BiCodingSource source) {
    Objects.requireNonNull(source, "source");
    // 阶段一：为不同图表建立各自的事实投影，同时按稳定业务 ID 消除重复采集记录。
    // 每个投影独立记录冲突，避免一个缺失字段让所有编码图表一起失效。
    DistinctResult<BiCodingSource.MergeRequestRecord> mergeRequestIdentities = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        ignored -> Boolean.TRUE);
    DistinctResult<BiCodingSource.MergeRequestRecord> mergeRequestConsistency = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        this::mergeRequestConsistency);
    DistinctResult<BiCodingSource.MergeRequestRecord> codeScaleFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        BiCodingSource.MergeRequestRecord::addedLines);
    DistinctResult<BiCodingSource.MergeRequestRecord> codeTrendFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        record -> new CodeTrendFact(record.mergedOn(), record.addedLines()));
    DistinctResult<BiCodingSource.MergeRequestRecord> mergeDateFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        BiCodingSource.MergeRequestRecord::mergedOn);
    DistinctResult<BiCodingSource.MergeRequestRecord> authorFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        BiCodingSource.MergeRequestRecord::author);
    DistinctResult<BiCodingSource.MergeRequestRecord> contributionFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        record -> new ContributionFact(record.author(), record.addedLines()));
    DistinctResult<BiCodingSource.MergeRequestRecord> moduleIncrementFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        record -> new ModuleIncrementFact(record.module(), record.addedLines()));

    DistinctResult<BiCodingSource.CodeReviewRecord> reviewIdentities = distinct(
        source.codeReviews(), this::codeReviewIdentity, ignored -> Boolean.TRUE);
    DistinctResult<BiCodingSource.CodeReviewRecord> reviewConsistency = distinct(
        source.codeReviews(), this::codeReviewIdentity, java.util.function.Function.identity());
    DistinctResult<BiCodingSource.CodeReviewRecord> reviewQualityFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::reviewQualityFact);
    DistinctResult<BiCodingSource.CodeReviewRecord> reviewCategoryFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::reviewCategoryFact);
    DistinctResult<BiCodingSource.CodeReviewRecord> moduleReviewFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::moduleReviewFact);
    DistinctResult<BiCodingSource.CodeReviewRecord> reviewScatterFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::reviewScatterFact);
    DistinctResult<BiCodingSource.CodeReviewRecord> scanFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::scanFact);
    DistinctResult<BiCodingSource.CodeReviewRecord> commentRateFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::commentRateFact);
    DistinctResult<BiCodingSource.CodeReviewRecord> densityTrendFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::densityTrendFact);
    DistinctResult<BiCodingSource.CommitRecord> commits = distinct(
        source.commits(), this::commitIdentity, java.util.function.Function.identity());

    // 阶段二：没有任何平台事实时返回 EMPTY；这和存在事实但分母不完整是两种状态。
    if (source.mergeRequests().isEmpty()
        && source.codeReviews().isEmpty()
        && source.commits().isEmpty()) {
      return empty(source);
    }

    // 阶段三：判定每个数据分区的输入完整性。这里的布尔值是分区级契约，
    // 后续组装页面时据此只隐藏无法可靠计算的分区。
    boolean mergeRequestIdentitiesComplete = source.mergeRequestIdsAvailable()
        && mergeRequestIdentities.complete();
    boolean reviewIdentitiesComplete = reviewIdentities.complete()
        && reviewIdentities.values().stream()
            .allMatch(record -> record.mergeRequestIdentity() != null);
    boolean commitIdentitiesComplete = commits.complete();
    boolean sourceConsistent = source.mergeRequestIdsAvailable()
        && mergeRequestConsistency.complete()
        && reviewConsistency.complete()
        && commitIdentitiesComplete;
    boolean mergeDatesComplete = mergeRequestIdentitiesComplete && mergeDateFacts.complete()
        && mergeDateFacts.values().stream()
        .allMatch(record -> record.mergedOn() != null);
    boolean codeScaleComplete = mergeRequestIdentitiesComplete
        && codeScaleFacts.complete()
        && source.codeScaleAvailable()
        && codeScaleFacts.values().stream().allMatch(record -> record.addedLines() != null
            && record.addedLines() >= 0);
    boolean codeTrendComplete = mergeRequestIdentitiesComplete
        && codeTrendFacts.complete()
        && source.codeScaleAvailable()
        && codeTrendFacts.values().stream().allMatch(record -> record.mergedOn() != null
            && record.addedLines() != null && record.addedLines() >= 0);
    boolean authorsComplete = mergeRequestIdentitiesComplete && authorFacts.complete()
        && authorFacts.values().stream().allMatch(record -> record.author() != null);
    boolean contributionsComplete = mergeRequestIdentitiesComplete
        && contributionFacts.complete()
        && source.codeScaleAvailable()
        && contributionFacts.values().stream().allMatch(record -> record.author() != null
            && record.addedLines() != null && record.addedLines() >= 0);
    boolean moduleIncrementsComplete = mergeRequestIdentitiesComplete
        && moduleIncrementFacts.complete()
        && source.codeScaleAvailable()
        && moduleIncrementFacts.values().stream().allMatch(record -> record.module() != null
            && record.addedLines() != null && record.addedLines() >= 0);
    boolean commitDetailsComplete = source.commitDetailsAvailable()
        && commitIdentitiesComplete
        && !commits.values().isEmpty()
        && commits.values().stream().allMatch(record -> record.committedOn() != null);
    boolean reviewInputsComplete = reviewIdentitiesComplete
        && reviewQualityFacts.complete()
        && source.reviewMetricsAvailable()
        && reviewQualityFacts.values().stream().allMatch(this::validReviewInputs)
        && consistentReviewedLinesByMergeRequest(reviewQualityFacts.values());
    boolean reviewDenominatorsComplete = reviewInputsComplete
        && reviewDenominatorsComplete(reviewQualityFacts.values());
    boolean categoriesConsistent = reviewIdentitiesComplete
        && reviewCategoryFacts.complete()
        && reviewCategoryFacts.values().stream().allMatch(this::validCategoryInputs)
        && categoriesConsistent(reviewCategoryFacts.values());
    boolean moduleReviewComplete = reviewIdentitiesComplete
        && moduleReviewFacts.complete()
        && source.reviewMetricsAvailable()
        && moduleReviewFacts.values().stream().allMatch(record -> record.module() != null)
        && moduleReviewFacts.values().stream().allMatch(this::validReviewInputs)
        && consistentReviewedLinesByMergeRequest(moduleReviewFacts.values())
        && moduleReviewDenominatorsComplete(moduleReviewFacts.values());
    List<BiCodingSource.CodeReviewRecord> reviewScatterValues =
        reviewScatterFacts.values().stream()
            .filter(this::positiveReviewDenominators)
            .toList();
    boolean reviewScatterComplete = reviewIdentitiesComplete
        && reviewScatterFacts.complete()
        && source.reviewMetricsAvailable()
        && !reviewScatterValues.isEmpty()
        && reviewScatterValues.stream().allMatch(this::validScatterInputs);
    List<BiCodingSource.CodeReviewRecord> scanValues = scanFacts.values().stream()
        .filter(this::validScanInputs)
        .toList();
    List<BiCodingSource.CodeReviewRecord> commentValues = commentRateFacts.values().stream()
        .filter(this::validCommentRateInputs)
        .toList();
    boolean scansComplete = reviewIdentitiesComplete && scanFacts.complete()
        && source.scanDataAvailable() && scanValues.size() == scanFacts.values().size();
    boolean commentsComplete = reviewIdentitiesComplete && commentRateFacts.complete()
        && source.commentRateDataAvailable()
        && commentValues.size() == commentRateFacts.values().size();
    List<BiCodingSource.CodeReviewRecord> densityTrendRecords = densityTrendFacts.values();
    List<BiCodingSource.CodeReviewRecord> densityTrendValues = removeConflictingReviewLines(
        densityTrendRecords.stream().filter(this::validDensityTrendInputs).toList());
    boolean densityTrendComplete = reviewIdentitiesComplete
        && densityTrendFacts.complete()
        && source.reviewMetricsAvailable()
        && !densityTrendValues.isEmpty()
        && densityTrendValues.size() == densityTrendRecords.size()
        && densityTrendRecords.stream().allMatch(this::validDensityTrendInputs);
    boolean qualityTrendComplete = commentsComplete && densityTrendComplete;

    List<BiCodingSource.CodeReviewRecord> codeReviews = reviewIdentities.values();

    // 阶段四：依据核对表公式计算指标，并将可用结果与分区状态一起返回给页面层。
    List<BiPageSection> sections = List.of(
        section("source-consistency", "来源一致性", sourceConsistent,
            source.mergeRequestIdsAvailable()
                ? "来源中存在同一稳定身份的冲突记录" : "部分记录缺少合并请求稳定身份"),
        section("code-trend", "代码增加趋势", codeTrendComplete,
            "部分合并请求缺少合并时间、新增代码行数或存在冲突"),
        section("submission-trend", "提交趋势", commitDetailsComplete && mergeDatesComplete,
            "平台尚未提供可按稳定提交 ID 和提交时间去重的提交明细"),
        section("contributors", "人员贡献", contributionsComplete,
            "部分合并请求缺少作者、新增代码行数或存在冲突"),
        section("module-increments", "模块代码增量", moduleIncrementsComplete,
            "部分合并请求缺少模块、新增代码行数或存在冲突"),
        dataSection("review-quality", "人工代码走查质量", codeReviews,
            reviewDenominatorsComplete, "被走查代码行数或实际工时不足，相关指标不可计算"),
        dataSection("review-categories", "代码走查问题分布", codeReviews,
            categoriesConsistent, "问题类别计数与有效问题总数不一致"),
        dataSection("module-review-quality", "模块人工代码走查质量", codeReviews,
            moduleReviewComplete, "部分走查记录缺少模块维度或计算分母"),
        dataSection("review-scatter", "人工代码走查散点", reviewScatterValues,
            reviewScatterFacts.values().size(), reviewScatterComplete,
            "部分走查记录缺少日期、模块或计算分母"),
        coverageSection("static-scan", "静态扫描", scanFacts.values(), scanValues,
            scansComplete, "静态扫描状态、日期或问题数"),
        coverageSection("comment-rate", "代码注释率", commentRateFacts.values(), commentValues,
            commentsComplete, "合法注释率或观测日期"),
        dataSection("quality-trend", "代码质量趋势",
            java.util.stream.Stream.concat(commentValues.stream(), densityTrendValues.stream()).toList(),
            commentRateFacts.values().size() + densityTrendRecords.size(), qualityTrendComplete,
            "部分记录缺少注释率或走查缺陷密度趋势输入"));

    Long addedLines = codeScaleComplete
        ? codeScaleFacts.values().stream()
            .mapToLong(BiCodingSource.MergeRequestRecord::addedLines).sum()
        : null;
    ReviewTotals reviewTotals = reviewDenominatorsComplete
        ? reviewTotals(reviewQualityFacts.values()) : null;
    BigDecimal reviewDensity = reviewTotals == null
        ? null : density(reviewTotals.problemCount(), reviewTotals.reviewedLines());
    BigDecimal reviewSpeed = reviewTotals == null
        ? null : speed(reviewTotals.reviewedLines(), reviewTotals.durationMinutes());
    BiCodingPageData data = new BiCodingPageData(
        new BiCodingPageData.Summary(
            addedLines,
            addedLines == null ? null
                : BigDecimal.valueOf(addedLines).divide(ONE_THOUSAND, 2, RoundingMode.HALF_UP),
            mergeRequestIdentitiesComplete ? (long) mergeRequestIdentities.values().size() : null,
            authorsComplete ? authorFacts.values().stream()
                .map(BiCodingSource.MergeRequestRecord::author).distinct().count() : null,
            reviewDensity,
            reviewSpeed,
            achieved(reviewDensity)),
        codeTrendComplete ? codeTrend(codeTrendFacts.values(), source.granularity()) : List.of(),
        mergeDatesComplete && commitDetailsComplete ? submissionTrend(
            mergeDateFacts.values(), commits.values(), source.granularity()) : List.of(),
        categoriesConsistent ? categories(reviewCategoryFacts.values()) : List.of(),
        contributionsComplete ? contributors(contributionFacts.values()) : List.of(),
        moduleIncrementsComplete ? moduleIncrements(moduleIncrementFacts.values()) : List.of(),
        scanPoints(scanValues),
        moduleReviewComplete ? moduleReviewQuality(moduleReviewFacts.values()) : List.of(),
        reviewScatterComplete ? reviewPoints(reviewScatterValues) : List.of(),
        commentRatePoints(commentValues),
        densityTrendComplete
            ? reviewDensityTrend(densityTrendValues, source.granularity()) : List.of(),
        coverage(scanFacts.values().size(), scanValues.size()),
        coverage(commentRateFacts.values().size(), commentValues.size()),
        coverage(densityTrendRecords.size(), densityTrendValues.size()));
    return BiPageResponse.create(
        "coding",
        pageStatus(sections),
        source.sourceVersion(),
        source.snapshotId(),
        RULE_VERSION,
        sections,
        traces(),
        data);
  }

  private BiPageResponse<BiCodingPageData> empty(BiCodingSource source) {
    BiCodingPageData data = new BiCodingPageData(
        new BiCodingPageData.Summary(0L, BigDecimal.ZERO, 0L, null, null, null, null),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), coverage(0, 0), coverage(0, 0), coverage(0, 0));
    return BiPageResponse.create(
        "coding",
        BiDataStatus.EMPTY,
        source.sourceVersion(),
        source.snapshotId(),
        RULE_VERSION,
        emptySections(),
        traces(),
        data);
  }

  private List<BiPageSection> emptySections() {
    String message = "当前产品版本没有编码阶段数据";
    return List.of(
        new BiPageSection("source-consistency", "来源一致性", BiDataStatus.READY, ""),
        new BiPageSection("code-trend", "代码增加趋势", BiDataStatus.EMPTY, message),
        new BiPageSection("submission-trend", "提交趋势", BiDataStatus.EMPTY, message),
        new BiPageSection("contributors", "人员贡献", BiDataStatus.EMPTY, message),
        new BiPageSection("module-increments", "模块代码增量", BiDataStatus.EMPTY, message),
        new BiPageSection("review-quality", "人工代码走查质量", BiDataStatus.EMPTY, message),
        new BiPageSection("review-categories", "代码走查问题分布", BiDataStatus.EMPTY, message),
        new BiPageSection("module-review-quality", "模块人工代码走查质量", BiDataStatus.EMPTY, message),
        new BiPageSection("review-scatter", "人工代码走查散点", BiDataStatus.EMPTY, message),
        new BiPageSection("static-scan", "静态扫描", BiDataStatus.EMPTY, message),
        new BiPageSection("comment-rate", "代码注释率", BiDataStatus.EMPTY, message),
        new BiPageSection("quality-trend", "代码质量趋势", BiDataStatus.EMPTY, message));
  }

  private List<BiCodingPageData.CodeTrendPoint> codeTrend(
      List<BiCodingSource.MergeRequestRecord> records,
      BiCodingSource.Granularity granularity) {
    Map<LocalDate, Long> increments = new java.util.TreeMap<>();
    for (BiCodingSource.MergeRequestRecord record : records) {
      increments.merge(bucket(record.mergedOn(), granularity), record.addedLines(), Long::sum);
    }
    long cumulative = 0;
    List<BiCodingPageData.CodeTrendPoint> points = new ArrayList<>();
    for (Map.Entry<LocalDate, Long> entry : increments.entrySet()) {
      cumulative += entry.getValue();
      points.add(new BiCodingPageData.CodeTrendPoint(entry.getKey(), entry.getValue(), cumulative));
    }
    return List.copyOf(points);
  }

  private List<BiCodingPageData.SubmissionPoint> submissionTrend(
      List<BiCodingSource.MergeRequestRecord> mergeRequests,
      List<BiCodingSource.CommitRecord> commits,
      BiCodingSource.Granularity granularity) {
    Map<LocalDate, Long> mergeRequestCounts = new java.util.TreeMap<>();
    mergeRequests.forEach(record -> mergeRequestCounts.merge(bucket(record.mergedOn(), granularity), 1L, Long::sum));
    Map<LocalDate, Long> commitCounts = new java.util.TreeMap<>();
    commits.forEach(record -> commitCounts.merge(bucket(record.committedOn(), granularity), 1L, Long::sum));
    Set<LocalDate> periods = new java.util.TreeSet<>(mergeRequestCounts.keySet());
    periods.addAll(commitCounts.keySet());
    return periods.stream()
        .map(period -> new BiCodingPageData.SubmissionPoint(
            period,
            commitCounts.getOrDefault(period, 0L),
            mergeRequestCounts.getOrDefault(period, 0L)))
        .toList();
  }

  private List<BiCodingPageData.CategoryBreakdown> categories(
      List<BiCodingSource.CodeReviewRecord> records) {
    long total = records.stream().mapToLong(BiCodingSource.CodeReviewRecord::effectiveProblemCount).sum();
    return List.of(
        category("代码规范", records.stream().mapToLong(BiCodingSource.CodeReviewRecord::codeSpecificationCount).sum(), total),
        category("代码逻辑规范", records.stream().mapToLong(BiCodingSource.CodeReviewRecord::codeLogicSpecificationCount).sum(), total),
        category("性能规范", records.stream().mapToLong(BiCodingSource.CodeReviewRecord::performanceSpecificationCount).sum(), total),
        category("设计规范", records.stream().mapToLong(BiCodingSource.CodeReviewRecord::designSpecificationCount).sum(), total),
        category("其他", records.stream().mapToLong(BiCodingSource.CodeReviewRecord::otherSpecificationCount).sum(), total));
  }

  private BiCodingPageData.CategoryBreakdown category(String name, long count, long total) {
    return new BiCodingPageData.CategoryBreakdown(name, count, percent(count, total));
  }

  private List<BiCodingPageData.Contributor> contributors(
      List<BiCodingSource.MergeRequestRecord> records) {
    Map<BiSourceDimension, NamedLongAccumulator> values = new LinkedHashMap<>();
    for (BiCodingSource.MergeRequestRecord record : records) {
      values.computeIfAbsent(record.author(), NamedLongAccumulator::new)
          .add(record.addedLines());
    }
    return values.values().stream()
        .map(value -> new BiCodingPageData.Contributor(value.dimension, value.value))
        .sorted(Comparator.comparingLong(BiCodingPageData.Contributor::addedLines).reversed())
        .toList();
  }

  private List<BiCodingPageData.ModuleIncrement> moduleIncrements(
      List<BiCodingSource.MergeRequestRecord> records) {
    Map<BiSourceDimension, NamedLongAccumulator> values = new LinkedHashMap<>();
    for (BiCodingSource.MergeRequestRecord record : records) {
      values.computeIfAbsent(record.module(), NamedLongAccumulator::new)
          .add(record.addedLines());
    }
    return values.values().stream()
        .map(value -> new BiCodingPageData.ModuleIncrement(value.dimension, value.value))
        .sorted(Comparator.comparingLong(BiCodingPageData.ModuleIncrement::addedLines).reversed())
        .toList();
  }

  private List<BiCodingPageData.ScanPoint> scanPoints(
      List<BiCodingSource.CodeReviewRecord> records) {
    return records.stream()
        .sorted(Comparator.comparing(BiCodingSource.CodeReviewRecord::reviewedOn))
        .map(record -> new BiCodingPageData.ScanPoint(
            record.codeReviewId(), record.reviewedOn(), record.scanStatus(), record.scanBugCount()))
        .toList();
  }

  private List<BiCodingPageData.ModuleReviewQuality> moduleReviewQuality(
      List<BiCodingSource.CodeReviewRecord> records) {
    Map<BiSourceDimension, ReviewAccumulator> values = new LinkedHashMap<>();
    for (BiCodingSource.CodeReviewRecord record : records) {
      values.computeIfAbsent(record.module(), ReviewAccumulator::new)
          .add(record);
    }
    return values.values().stream().map(value -> {
      ReviewTotals totals = value.totals();
      BigDecimal density = density(totals.problemCount(), totals.reviewedLines());
      return new BiCodingPageData.ModuleReviewQuality(
          value.dimension, density, speed(totals.reviewedLines(), totals.durationMinutes()), achieved(density));
    }).toList();
  }

  private List<BiCodingPageData.ReviewPoint> reviewPoints(
      List<BiCodingSource.CodeReviewRecord> records) {
    return records.stream()
        .sorted(Comparator.comparing(BiCodingSource.CodeReviewRecord::reviewedOn))
        .map(record -> {
          BigDecimal density = density(record.effectiveProblemCount(), record.reviewedLines());
          return new BiCodingPageData.ReviewPoint(
              record.codeReviewId(),
              record.reviewedOn(),
              record.module(),
              speedKloc(record.reviewedLines(), record.reviewDurationMinutes()),
              density,
              achieved(density));
        }).toList();
  }

  private List<BiCodingPageData.CommentRatePoint> commentRatePoints(
      List<BiCodingSource.CodeReviewRecord> records) {
    return records.stream()
        .sorted(Comparator.comparing(BiCodingSource.CodeReviewRecord::reviewedOn))
        .map(record -> new BiCodingPageData.CommentRatePoint(
            record.codeReviewId(), record.reviewedOn(), record.commentRate(), record.commentRateSource()))
        .toList();
  }

  private List<BiCodingPageData.ReviewDensityTrendPoint> reviewDensityTrend(
      List<BiCodingSource.CodeReviewRecord> records,
      BiCodingSource.Granularity granularity) {
    Map<LocalDate, ReviewAccumulator> values = new java.util.TreeMap<>();
    for (BiCodingSource.CodeReviewRecord record : records) {
      values.computeIfAbsent(bucket(record.reviewedOn(), granularity), ignored -> new ReviewAccumulator(null))
          .add(record);
    }
    return values.entrySet().stream().map(entry -> {
      ReviewTotals totals = entry.getValue().totals();
      return new BiCodingPageData.ReviewDensityTrendPoint(
          entry.getKey(), density(totals.problemCount(), totals.reviewedLines()));
    }).toList();
  }

  private ReviewTotals reviewTotals(List<BiCodingSource.CodeReviewRecord> records) {
    ReviewAccumulator accumulator = new ReviewAccumulator(null);
    records.forEach(accumulator::add);
    return accumulator.totals();
  }

  private boolean reviewDenominatorsComplete(List<BiCodingSource.CodeReviewRecord> records) {
    ReviewTotals totals = reviewTotals(records);
    return totals.reviewedLines() > 0 && totals.durationMinutes().signum() > 0;
  }

  private boolean moduleReviewDenominatorsComplete(
      List<BiCodingSource.CodeReviewRecord> records) {
    Map<BiSourceDimension, ReviewAccumulator> modules = new LinkedHashMap<>();
    for (BiCodingSource.CodeReviewRecord record : records) {
      modules.computeIfAbsent(record.module(), ReviewAccumulator::new).add(record);
    }
    return !modules.isEmpty()
        && modules.values().stream()
            .map(ReviewAccumulator::totals)
            .allMatch(
                totals ->
                    totals.reviewedLines() > 0 && totals.durationMinutes().signum() > 0);
  }

  private boolean categoriesConsistent(List<BiCodingSource.CodeReviewRecord> records) {
    return records.stream().allMatch(record -> record.effectiveProblemCount().longValue()
        == record.codeSpecificationCount()
            + record.codeLogicSpecificationCount()
            + record.performanceSpecificationCount()
            + record.designSpecificationCount()
            + record.otherSpecificationCount());
  }

  private BigDecimal density(long problems, long reviewedLines) {
    return reviewedLines <= 0 ? null : BigDecimal.valueOf(problems)
        .multiply(ONE_THOUSAND)
        .divide(BigDecimal.valueOf(reviewedLines), 2, RoundingMode.HALF_UP);
  }

  private BigDecimal speed(long reviewedLines, BigDecimal durationMinutes) {
    return durationMinutes == null || durationMinutes.signum() <= 0 ? null
        : BigDecimal.valueOf(reviewedLines).multiply(MINUTES_PER_HOUR)
            .divide(durationMinutes, 2, RoundingMode.HALF_UP);
  }

  private BigDecimal speedKloc(long reviewedLines, BigDecimal durationMinutes) {
    return durationMinutes == null || durationMinutes.signum() <= 0 ? null
        : BigDecimal.valueOf(reviewedLines).multiply(MINUTES_PER_HOUR)
            .divide(ONE_THOUSAND.multiply(durationMinutes), 2, RoundingMode.HALF_UP);
  }

  private BigDecimal percent(long value, long total) {
    return total <= 0 ? null : BigDecimal.valueOf(value).multiply(new BigDecimal("100"))
        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
  }

  private Boolean achieved(BigDecimal density) {
    return density == null ? null
        : density.compareTo(MIN_REVIEW_DENSITY) >= 0 && density.compareTo(MAX_REVIEW_DENSITY) <= 0;
  }

  private LocalDate bucket(LocalDate date, BiCodingSource.Granularity granularity) {
    return granularity == BiCodingSource.Granularity.WEEK
        ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
  }

  private BiPageSection section(String key, String label, boolean ready, String incompleteMessage) {
    return new BiPageSection(key, label, ready ? BiDataStatus.READY : BiDataStatus.INCOMPLETE,
        ready ? "" : incompleteMessage);
  }

  private BiPageSection dataSection(
      String key,
      String label,
      List<?> values,
      boolean ready,
      String incompleteMessage) {
    return dataSection(key, label, values, values.size(), ready, incompleteMessage);
  }

  private BiPageSection dataSection(
      String key,
      String label,
      List<?> values,
      int totalValues,
      boolean ready,
      String incompleteMessage) {
    if (values.isEmpty() && totalValues == 0) {
      return new BiPageSection(key, label, BiDataStatus.EMPTY, "当前范围没有" + label + "数据");
    }
    if (values.isEmpty()) {
      return new BiPageSection(key, label, BiDataStatus.INCOMPLETE, incompleteMessage);
    }
    return section(key, label, ready, incompleteMessage);
  }

  private BiPageSection coverageSection(
      String key,
      String label,
      List<?> total,
      List<?> valid,
      boolean complete,
      String requiredInputs) {
    if (valid.isEmpty() && total.isEmpty()) {
      return new BiPageSection(key, label, BiDataStatus.EMPTY, "当前范围没有可计算的" + label + "数据");
    }
    if (valid.isEmpty()) {
      return new BiPageSection(key, label, BiDataStatus.INCOMPLETE,
          "有效观测 0/" + total.size() + "；其余记录缺少" + requiredInputs);
    }
    String message = complete
        ? ""
        : "有效观测 " + valid.size() + "/" + total.size() + "；其余记录缺少" + requiredInputs;
    return new BiPageSection(
        key, label, complete ? BiDataStatus.READY : BiDataStatus.INCOMPLETE, message);
  }

  private BiCodingPageData.Coverage coverage(long total, long valid) {
    return new BiCodingPageData.Coverage(total, valid, percent(valid, total));
  }

  private BiDataStatus pageStatus(List<BiPageSection> sections) {
    return sections.stream().anyMatch(section -> section.status() == BiDataStatus.INCOMPLETE)
        ? BiDataStatus.INCOMPLETE : BiDataStatus.READY;
  }

  private List<BiMetricTrace> traces() {
    return List.of(
        new BiMetricTrace(
            List.of("CD-01", "CD-02", "CD-03", "CD-03A", "CD-04", "CD-05", "CD-06",
                "CD-07", "CD-08", "CD-09", "CD-10", "CD-12"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField(
                    "合并请求稳定身份",
                    "code_review_match_mode_records.source_instance + merge_request_iid / "
                        + "code_review_formal_records.project_id + merge_request_id"),
                new BiMetricTrace.SourceField(
                    "合并时间",
                    "code_review_match_mode_records.merged_at_source / "
                        + "code_review_formal_records.merged_at_source"),
                new BiMetricTrace.SourceField(
                    "作者名称",
                    "code_review_match_mode_records.author_name / "
                        + "code_review_formal_records.author_name"),
                new BiMetricTrace.SourceField(
                    "模块名称",
                    "code_review_match_mode_records.module_name / "
                        + "code_review_formal_records.module_name"),
                new BiMetricTrace.SourceField(
                    "新增代码行数",
                    "code_review_match_mode_records.added_lines / "
                        + "code_review_formal_records.added_lines")),
            "按来源感知的合并请求稳定身份去重新增行；作者和模块按冻结来源快照内名称值分组；"
                + "按日/周聚合并累计；KLOC=新增行数/1000",
            "BI服务端"),
        new BiMetricTrace(
            List.of("CD-13", "CD-14", "CD-15"),
            "数据采集平台 GitLab 事实层",
            List.of(
                new BiMetricTrace.SourceField(
                    "稳定提交 SHA", "merge_request_commit_fact.commit_sha"),
                new BiMetricTrace.SourceField(
                    "提交时间", "merge_request_commit_fact.committed_at_source")),
            "按来源实例、项目和提交 SHA 去重，再按日/周聚合提交数量",
            "BI服务端"),
        new BiMetricTrace(
            List.of(
                "CD-16", "CD-16A", "CD-17", "CD-18", "CD-19", "CD-20", "CD-21", "CD-22",
                "CD-23", "CD-24", "CD-25", "CD-26", "CD-27", "CD-28", "CD-29"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField(
                    "代码走查记录 ID",
                    "code_review_match_mode_records.id / code_review_formal_records.id"),
                new BiMetricTrace.SourceField(
                    "人工走查日期",
                    "code_review_match_mode_records.code_walkthrough_date / "
                        + "code_review_formal_records.code_walkthrough_date"),
                new BiMetricTrace.SourceField(
                    "被走查代码行数",
                    "code_review_match_mode_records.added_lines / "
                        + "code_review_formal_records.added_lines"),
                new BiMetricTrace.SourceField(
                    "人工走查实际工时（分钟）",
                    "code_review_match_mode_records.review_duration_minutes / "
                        + "code_review_formal_records.review_duration_minutes"),
                new BiMetricTrace.SourceField(
                    "人工走查有效问题数",
                    "code_review_match_mode_records.defect_count / "
                        + "code_review_formal_records.defect_count")),
            "缺陷密度=有效问题数*1000/去重被走查行数；速率=被走查行数/(分钟/60)；密度区间[3,12]达标",
            "BI服务端"),
        new BiMetricTrace(
            List.of("CD-30", "CD-31", "CD-32", "CD-33", "CD-34", "CD-35", "CD-36", "CD-37", "CD-38", "CD-39"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField(
                    "五类代码走查问题数",
                    "code_review_match_mode_records.*_specification_count / "
                        + "code_review_formal_records.*_specification_count"),
                new BiMetricTrace.SourceField(
                    "静态扫描状态",
                    "code_review_match_mode_records.scan_status / "
                        + "code_review_formal_records.scan_status"),
                new BiMetricTrace.SourceField(
                    "静态扫描问题数",
                    "code_review_match_mode_records.scan_bug_count / "
                        + "code_review_formal_records.scan_bug_count"),
                new BiMetricTrace.SourceField(
                    "代码注释率",
                    "code_review_match_mode_records.comment_rate / "
                        + "code_review_formal_records.comment_rate"),
                new BiMetricTrace.SourceField(
                    "注释率统计来源",
                    "code_review_formal_records.comment_rate_source")),
            "类别占比=类别问题数/有效问题总数；注释率直接使用上游值；趋势按总体KLOC公式计算",
            "BI服务端"));
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private Long codeReviewIdentity(BiCodingSource.CodeReviewRecord record) {
    return record.codeReviewId() > 0 ? record.codeReviewId() : null;
  }

  private String commitIdentity(BiCodingSource.CommitRecord record) {
    return hasText(record.commitId()) ? record.commitId() : null;
  }

  private MergeRequestConsistencyFact mergeRequestConsistency(
      BiCodingSource.MergeRequestRecord record) {
    return new MergeRequestConsistencyFact(
        record.mergedOn(), record.author(), record.module(), record.addedLines());
  }

  private ReviewQualityFact reviewQualityFact(BiCodingSource.CodeReviewRecord record) {
    return new ReviewQualityFact(
        record.mergeRequestIdentity(),
        record.reviewedLines(),
        record.reviewDurationMinutes(),
        record.effectiveProblemCount());
  }

  private ReviewCategoryFact reviewCategoryFact(BiCodingSource.CodeReviewRecord record) {
    return new ReviewCategoryFact(
        record.effectiveProblemCount(),
        record.codeSpecificationCount(),
        record.codeLogicSpecificationCount(),
        record.performanceSpecificationCount(),
        record.designSpecificationCount(),
        record.otherSpecificationCount());
  }

  private ModuleReviewFact moduleReviewFact(BiCodingSource.CodeReviewRecord record) {
    return new ModuleReviewFact(
        record.mergeRequestIdentity(),
        record.module(),
        record.reviewedLines(),
        record.reviewDurationMinutes(),
        record.effectiveProblemCount());
  }

  private ReviewScatterFact reviewScatterFact(BiCodingSource.CodeReviewRecord record) {
    return new ReviewScatterFact(
        record.reviewedOn(),
        record.mergeRequestIdentity(),
        record.module(),
        record.reviewedLines(),
        record.reviewDurationMinutes(),
        record.effectiveProblemCount());
  }

  private ScanFact scanFact(BiCodingSource.CodeReviewRecord record) {
    return new ScanFact(record.reviewedOn(), record.scanStatus(), record.scanBugCount());
  }

  private CommentRateFact commentRateFact(BiCodingSource.CodeReviewRecord record) {
    return new CommentRateFact(
        record.reviewedOn(), record.commentRate(), record.commentRateSource());
  }

  private DensityTrendFact densityTrendFact(BiCodingSource.CodeReviewRecord record) {
    return new DensityTrendFact(
        record.reviewedOn(),
        record.mergeRequestIdentity(),
        record.reviewedLines(),
        record.effectiveProblemCount());
  }

  private boolean validReviewInputs(BiCodingSource.CodeReviewRecord record) {
    return record.reviewedLines() != null && record.reviewedLines() >= 0
        && record.reviewDurationMinutes() != null
        && record.reviewDurationMinutes().signum() >= 0
        && record.effectiveProblemCount() != null
        && record.effectiveProblemCount() >= 0;
  }

  private boolean validCategoryInputs(BiCodingSource.CodeReviewRecord record) {
    return record.effectiveProblemCount() != null && record.effectiveProblemCount() >= 0
        && record.codeSpecificationCount() != null && record.codeSpecificationCount() >= 0
        && record.codeLogicSpecificationCount() != null
        && record.codeLogicSpecificationCount() >= 0
        && record.performanceSpecificationCount() != null
        && record.performanceSpecificationCount() >= 0
        && record.designSpecificationCount() != null && record.designSpecificationCount() >= 0
        && record.otherSpecificationCount() != null && record.otherSpecificationCount() >= 0;
  }

  private boolean validScanInputs(BiCodingSource.CodeReviewRecord record) {
    return record.reviewedOn() != null
        && hasText(record.scanStatus())
        && (record.scanBugCount() == null || record.scanBugCount() >= 0);
  }

  private boolean validCommentRateInputs(BiCodingSource.CodeReviewRecord record) {
    return record.reviewedOn() != null
        && record.commentRate() != null
        && record.commentRate().signum() >= 0;
  }

  private boolean validScatterInputs(BiCodingSource.CodeReviewRecord record) {
    return record.reviewedOn() != null
        && record.module() != null
        && validReviewInputs(record)
        && record.reviewedLines() > 0
        && record.reviewDurationMinutes().signum() > 0;
  }

  private boolean positiveReviewDenominators(BiCodingSource.CodeReviewRecord record) {
    return record.reviewedLines() != null
        && record.reviewedLines() > 0
        && record.reviewDurationMinutes() != null
        && record.reviewDurationMinutes().signum() > 0;
  }

  private boolean validDensityTrendInputs(BiCodingSource.CodeReviewRecord record) {
    return record.reviewedOn() != null
        && record.reviewedLines() != null && record.reviewedLines() > 0
        && record.effectiveProblemCount() != null && record.effectiveProblemCount() >= 0;
  }

  private boolean consistentReviewedLinesByMergeRequest(
      List<BiCodingSource.CodeReviewRecord> records) {
    Map<BiCodingSource.MergeRequestIdentity, Long> reviewedLinesByMergeRequest =
        new LinkedHashMap<>();
    for (BiCodingSource.CodeReviewRecord record : records) {
      if (record.mergeRequestIdentity() == null || record.reviewedLines() == null) {
        return false;
      }
      Long previous = reviewedLinesByMergeRequest.putIfAbsent(
          record.mergeRequestIdentity(), record.reviewedLines());
      if (previous != null && previous.longValue() != record.reviewedLines()) {
        return false;
      }
    }
    return true;
  }

  private List<BiCodingSource.CodeReviewRecord> removeConflictingReviewLines(
      List<BiCodingSource.CodeReviewRecord> records) {
    Map<BiCodingSource.MergeRequestIdentity, Set<Long>> linesByMergeRequest = new LinkedHashMap<>();
    for (BiCodingSource.CodeReviewRecord record : records) {
      if (record.mergeRequestIdentity() != null && record.reviewedLines() != null) {
        linesByMergeRequest.computeIfAbsent(record.mergeRequestIdentity(), ignored -> new java.util.LinkedHashSet<>())
            .add(record.reviewedLines());
      }
    }
    Set<BiCodingSource.MergeRequestIdentity> conflicting = linesByMergeRequest.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toSet());
    return records.stream()
        .filter(record -> !conflicting.contains(record.mergeRequestIdentity()))
        .toList();
  }

  private <K, T, S> DistinctResult<T> distinct(
      List<T> values,
      java.util.function.Function<T, K> identity,
      java.util.function.Function<T, S> signature) {
    Map<K, T> distinct = new LinkedHashMap<>();
    Map<K, S> signatures = new LinkedHashMap<>();
    boolean conflict = false;
    boolean invalidIdentity = false;
    for (T value : values) {
      K key = identity.apply(value);
      if (key == null) {
        invalidIdentity = true;
        continue;
      }
      S currentSignature = signature.apply(value);
      T previous = distinct.putIfAbsent(key, value);
      if (previous == null) {
        signatures.put(key, currentSignature);
      } else if (!Objects.equals(signatures.get(key), currentSignature)) {
        conflict = true;
      }
    }
    return new DistinctResult<>(List.copyOf(distinct.values()), invalidIdentity, conflict);
  }

  private record DistinctResult<T>(
      List<T> values,
      boolean invalidIdentity,
      boolean conflict) {
    private boolean complete() {
      return !invalidIdentity && !conflict;
    }
  }

  private record ReviewTotals(long reviewedLines, BigDecimal durationMinutes, long problemCount) {}

  private record MergeRequestConsistencyFact(
      LocalDate mergedOn,
      BiSourceDimension author,
      BiSourceDimension module,
      Long addedLines) {}

  private record CodeTrendFact(LocalDate mergedOn, Long addedLines) {}

  private record ContributionFact(BiSourceDimension author, Long addedLines) {}

  private record ModuleIncrementFact(BiSourceDimension module, Long addedLines) {}

  private record ReviewQualityFact(
      BiCodingSource.MergeRequestIdentity mergeRequestIdentity,
      Long reviewedLines,
      BigDecimal reviewDurationMinutes,
      Long effectiveProblemCount) {}

  private record ReviewCategoryFact(
      Long effectiveProblemCount,
      Long codeSpecificationCount,
      Long codeLogicSpecificationCount,
      Long performanceSpecificationCount,
      Long designSpecificationCount,
      Long otherSpecificationCount) {}

  private record ModuleReviewFact(
      BiCodingSource.MergeRequestIdentity mergeRequestIdentity,
      BiSourceDimension module,
      Long reviewedLines,
      BigDecimal reviewDurationMinutes,
      Long effectiveProblemCount) {}

  private record ReviewScatterFact(
      LocalDate reviewedOn,
      BiCodingSource.MergeRequestIdentity mergeRequestIdentity,
      BiSourceDimension module,
      Long reviewedLines,
      BigDecimal reviewDurationMinutes,
      Long effectiveProblemCount) {}

  private record ScanFact(LocalDate reviewedOn, String scanStatus, Long scanBugCount) {}

  private record CommentRateFact(
      LocalDate reviewedOn,
      BigDecimal commentRate,
      String commentRateSource) {}

  private record DensityTrendFact(
      LocalDate reviewedOn,
      BiCodingSource.MergeRequestIdentity mergeRequestIdentity,
      Long reviewedLines,
      Long effectiveProblemCount) {}

  private static final class NamedLongAccumulator {
    private final BiSourceDimension dimension;
    private long value;

    private NamedLongAccumulator(BiSourceDimension dimension) {
      this.dimension = dimension;
    }

    private void add(long increment) {
      value += increment;
    }
  }

  private final class ReviewAccumulator {
    private final BiSourceDimension dimension;
    private final Map<BiCodingSource.MergeRequestIdentity, Long> reviewedLinesByMergeRequest =
        new LinkedHashMap<>();
    private BigDecimal durationMinutes = BigDecimal.ZERO;
    private long problemCount;

    private ReviewAccumulator(BiSourceDimension dimension) {
      this.dimension = dimension;
    }

    private void add(BiCodingSource.CodeReviewRecord record) {
      reviewedLinesByMergeRequest.putIfAbsent(
          record.mergeRequestIdentity(), record.reviewedLines());
      durationMinutes = durationMinutes.add(record.reviewDurationMinutes());
      problemCount += record.effectiveProblemCount();
    }

    private ReviewTotals totals() {
      return new ReviewTotals(
          reviewedLinesByMergeRequest.values().stream().mapToLong(Long::longValue).sum(),
          durationMinutes,
          problemCount);
    }
  }
}
