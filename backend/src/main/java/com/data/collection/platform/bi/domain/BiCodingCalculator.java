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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 严格实现 CD-01 至 CD-39（含 CD-38A）人工确认口径的编码阶段计算器。 */
public final class BiCodingCalculator {
  private static final BigDecimal MIN_REVIEW_DENSITY = new BigDecimal("3.00");
  private static final BigDecimal MAX_REVIEW_DENSITY = new BigDecimal("12.00");
  private static final BigDecimal ONE_THOUSAND = new BigDecimal("1000");
  private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal("60");
  private static final String RULE_VERSION = "bi-coding-v5";

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
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> mergeRequestIdentities = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        ignored -> Boolean.TRUE);
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> mergeRequestConsistency = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        this::mergeRequestConsistency);
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> codeScaleFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        BiCodingSource.MergeRequestRecord::addedLines);
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> codeTrendFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        record -> new CodeTrendFact(record.mergedOn(), record.addedLines()));
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> mergeDateFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        BiCodingSource.MergeRequestRecord::mergedOn);
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> authorFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        BiCodingSource.MergeRequestRecord::author);
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> contributionFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        record -> new ContributionFact(record.author(), record.addedLines()));
    DistinctResult<BiCodingSource.MergeRequestRecord, BiCodingSource.MergeRequestIdentity> moduleIncrementFacts = distinct(
        source.mergeRequests(), BiCodingSource.MergeRequestRecord::mergeRequestIdentity,
        record -> new ModuleIncrementFact(record.module(), record.addedLines()));

    DistinctResult<BiCodingSource.CodeReviewRecord, Long> reviewIdentities = distinct(
        source.codeReviews(), this::codeReviewIdentity, ignored -> Boolean.TRUE);
    DistinctResult<BiCodingSource.CodeReviewRecord, Long> reviewConsistency = distinct(
        source.codeReviews(), this::codeReviewIdentity, java.util.function.Function.identity());
    DistinctResult<BiCodingSource.CodeReviewRecord, Long> reviewQualityFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::reviewQualityFact);
    DistinctResult<BiCodingSource.CodeReviewRecord, Long> reviewCategoryFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::reviewCategoryFact);
    DistinctResult<BiCodingSource.CodeReviewRecord, Long> moduleReviewFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::moduleReviewFact);
    DistinctResult<BiCodingSource.CodeReviewRecord, Long> reviewScatterFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::reviewScatterFact);
    DistinctResult<BiCodingSource.CodeReviewRecord, Long> commentRateFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::commentRateFact);
    DistinctResult<BiCodingSource.CodeReviewRecord, Long> densityTrendFacts = distinct(
        source.codeReviews(), this::codeReviewIdentity, this::densityTrendFact);
    DistinctResult<BiCodingSource.CommitRecord, String> commits = distinct(
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
    // 注释率趋势按 CD-38A：稳定走查记录 ID 去重后，同一 ID 核心字段冲突的记录整组剔除，
    // 不按输入顺序任选一条；坏行只从自身序列剔除，与 CD-39 的覆盖率语义保持一致。
    List<BiCodingSource.CodeReviewRecord> commentRateRecords = commentRateFacts.values().stream()
        .filter(record -> !commentRateFacts.conflictingIdentities()
            .contains(codeReviewIdentity(record)))
        .toList();
    List<BiCodingSource.CodeReviewRecord> commentValues = commentRateRecords.stream()
        .filter(this::validCommentRateInputs)
        .toList();
    boolean commentsComplete = reviewIdentitiesComplete && commentRateFacts.complete()
        && source.commentRateDataAvailable()
        && commentValues.size() == commentRateFacts.values().size();
    List<BiCodingSource.CodeReviewRecord> densityTrendRecords = densityTrendFacts.values();
    List<BiCodingSource.CodeReviewRecord> densityTrendValues = removeConflictingReviewLines(
        densityTrendRecords.stream()
            .filter(record -> !densityTrendFacts.conflictingIdentities()
                .contains(codeReviewIdentity(record)))
            .filter(this::validDensityTrendInputs)
            .toList());
    // 密度趋势采用覆盖率语义：坏行（缺走查日期、被走查行数≤0、缺陷数缺失或同 MR 行数冲突）只从自身序列剔除，
    // 不再让整条曲线置空；是否全覆盖仅决定分区状态标签，与 comment-rate 保持一致。
    boolean densityTrendFullyCovered = reviewIdentitiesComplete
        && densityTrendFacts.complete()
        && source.reviewDensityDataAvailable()
        && densityTrendValues.size() == densityTrendRecords.size();
    boolean qualityTrendComplete = commentsComplete && densityTrendFullyCovered;

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
        coverageSection("comment-rate", "代码注释率", "走查记录", commentRateFacts.values(),
            commentValues, commentsComplete, "合法注释率或观测日期"),
        qualityTrendSection(
            commentRateFacts.values().size(), commentValues.size(),
            densityTrendRecords.size(), densityTrendValues.size(), qualityTrendComplete,
            "合法注释率或有效密度输入（走查日期、被走查行数大于 0、缺陷数非负且同 MR 行数一致）"));

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
        moduleReviewComplete ? moduleReviewQuality(moduleReviewFacts.values()) : List.of(),
        reviewScatterComplete ? reviewPoints(reviewScatterValues) : List.of(),
        commentRateTrend(commentValues, source.granularity()),
        reviewDensityTrend(densityTrendValues, source.granularity()),
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
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), coverage(0, 0), coverage(0, 0));
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

  /**
   * 按 CD-38A 把合法注释率观测聚合为趋势点：与密度趋势使用同一日期桶，桶内先累加原始
   * {@link BigDecimal}，最后一次性除以有效记录数并保留 2 位小数，避免逐条四舍五入引入偏差。
   * 没有合法记录的时间桶不生成点位，由页面与密度轨对齐后显示为 {@code null}。
   *
   * @param records     已去重、无冲突且通过 {@code validCommentRateInputs} 的走查记录
   * @param granularity 请求的时间粒度；周粒度以周一为桶值
   * @return 按周期升序、每个周期至多一个元素的注释率趋势
   */
  private List<BiCodingPageData.CommentRateTrendPoint> commentRateTrend(
      List<BiCodingSource.CodeReviewRecord> records,
      BiCodingSource.Granularity granularity) {
    Map<LocalDate, CommentRateAccumulator> values = new java.util.TreeMap<>();
    for (BiCodingSource.CodeReviewRecord record : records) {
      values.computeIfAbsent(bucket(record.reviewedOn(), granularity),
              ignored -> new CommentRateAccumulator())
          .add(record.commentRate());
    }
    return values.entrySet().stream()
        .map(entry -> new BiCodingPageData.CommentRateTrendPoint(
            entry.getKey(), entry.getValue().average()))
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

  /**
   * 构造记录级覆盖率分区。{@code observationLabel} 用于明确覆盖率统计的是走查记录数，
   * 而不是趋势周期数，避免页面把 {@code valid/total} 误读为时间桶覆盖。
   */
  private BiPageSection coverageSection(
      String key,
      String label,
      String observationLabel,
      List<?> total,
      List<?> valid,
      boolean complete,
      String requiredInputs) {
    if (valid.isEmpty() && total.isEmpty()) {
      return new BiPageSection(key, label, BiDataStatus.EMPTY, "当前范围没有可计算的" + label + "数据");
    }
    if (valid.isEmpty()) {
      return new BiPageSection(key, label, BiDataStatus.INCOMPLETE,
          "有效" + observationLabel + " 0/" + total.size() + "；其余记录缺少" + requiredInputs);
    }
    String message = complete
        ? ""
        : "有效" + observationLabel + " " + valid.size() + "/" + total.size()
            + "；其余记录缺少" + requiredInputs;
    return new BiPageSection(
        key, label, complete ? BiDataStatus.READY : BiDataStatus.INCOMPLETE, message);
  }

  /**
   * 构造同时依赖注释率和密度两个事实族的质量趋势分区，分别披露两组记录级覆盖率。
   *
   * @param commentTotal 注释率事实去重后的记录总数
   * @param commentValid 通过注释率合法性与冲突校验的记录数
   * @param densityTotal 密度趋势事实去重后的记录总数
   * @param densityValid 通过密度输入校验的记录数
   * @param complete 两条趋势是否都完整
   * @param requiredInputs 不完整时的缺失输入说明
   * @return 质量趋势分区状态和不完整原因
   */
  private BiPageSection qualityTrendSection(
      int commentTotal,
      int commentValid,
      int densityTotal,
      int densityValid,
      boolean complete,
      String requiredInputs) {
    if (commentTotal == 0 && densityTotal == 0) {
      return new BiPageSection("quality-trend", "代码质量趋势", BiDataStatus.EMPTY,
          "当前范围没有可计算的代码质量趋势数据");
    }
    if (complete) {
      return new BiPageSection("quality-trend", "代码质量趋势", BiDataStatus.READY, "");
    }
    String message = "注释率有效走查记录 " + commentValid + "/" + commentTotal
        + "；密度有效走查记录 " + densityValid + "/" + densityTotal
        + "；其余记录缺少" + requiredInputs;
    return new BiPageSection("quality-trend", "代码质量趋势", BiDataStatus.INCOMPLETE, message);
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
            List.of("CD-30", "CD-31", "CD-32", "CD-33", "CD-34", "CD-35", "CD-38", "CD-38A",
                "CD-39"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField(
                    "五类代码走查问题数",
                    "code_review_match_mode_records.*_specification_count / "
                        + "code_review_formal_records.*_specification_count"),
                new BiMetricTrace.SourceField(
                    "代码注释率",
                    "code_review_match_mode_records.comment_rate / "
                        + "code_review_formal_records.comment_rate"),
                new BiMetricTrace.SourceField(
                    "注释率统计来源",
                    "code_review_formal_records.comment_rate_source")),
            "类别占比=类别问题数/有效问题总数；注释率=周期内合法注释率非加权算术平均，忽略 NULL，"
                + "保留 2 位小数；密度按 CD-39 以总体 KLOC 公式计算",
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

  private CommentRateFact commentRateFact(BiCodingSource.CodeReviewRecord record) {
    // commentRateSource 是可选追溯信息，不属于注释率业务值；来源为空/非空不能构成事实冲突。
    return new CommentRateFact(record.reviewedOn(), record.commentRate());
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

  /** 密度趋势的单行输入契约：除日期与数值外还要求稳定合并请求身份，否则无法执行 CD-39 的同 MR 行数只计一次。 */
  private boolean validDensityTrendInputs(BiCodingSource.CodeReviewRecord record) {
    return record.reviewedOn() != null
        && record.mergeRequestIdentity() != null
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

  /**
   * 按稳定身份对来源记录去重，并记录哪些身份出现了核心字段冲突。
   *
   * @param values    来源记录，顺序即来源顺序
   * @param identity  稳定身份提取函数；返回 {@code null} 表示该记录无法确定身份
   * @param signature 该事实族的完整性签名；同一身份出现不同签名即判定为冲突
   * @return 每个身份保留的首条值、冲突身份集合与完整性标志
   */
  private <K, T, S> DistinctResult<T, K> distinct(
      List<T> values,
      java.util.function.Function<T, K> identity,
      java.util.function.Function<T, S> signature) {
    Map<K, T> distinct = new LinkedHashMap<>();
    Map<K, S> signatures = new LinkedHashMap<>();
    Set<K> conflictingIdentities = new LinkedHashSet<>();
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
        conflictingIdentities.add(key);
      }
    }
    return new DistinctResult<>(
        List.copyOf(distinct.values()),
        Set.copyOf(conflictingIdentities),
        invalidIdentity,
        !conflictingIdentities.isEmpty());
  }

  /**
   * 稳定身份去重结果。
   *
   * @param values                每个稳定身份保留的首条值，顺序与输入一致
   * @param conflictingIdentities 核心字段与其他同身份记录冲突的稳定身份；调用方必须整组剔除，
   *                              不得按输入顺序把首条当作权威值
   * @param invalidIdentity       是否存在无法确定稳定身份的记录
   * @param conflict              是否存在同一稳定身份的核心字段冲突
   */
  private record DistinctResult<T, K>(
      List<T> values,
      Set<K> conflictingIdentities,
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

  private record CommentRateFact(
      LocalDate reviewedOn,
      BigDecimal commentRate) {}

  private record DensityTrendFact(
      LocalDate reviewedOn,
      BiCodingSource.MergeRequestIdentity mergeRequestIdentity,
      Long reviewedLines,
      Long effectiveProblemCount) {}

  /** 注释率时间桶累加器：先累加原始值，最后一次性求平均，避免逐条舍入。 */
  private static final class CommentRateAccumulator {
    private BigDecimal sum = BigDecimal.ZERO;
    private long count;

    private void add(BigDecimal commentRate) {
      sum = sum.add(commentRate);
      count++;
    }

    private BigDecimal average() {
      return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
  }

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
      // 工时仅速率类消费方需要；密度趋势子集不要求工时，缺失时按零参与不参与处理。
      if (record.reviewDurationMinutes() != null) {
        durationMinutes = durationMinutes.add(record.reviewDurationMinutes());
      }
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
