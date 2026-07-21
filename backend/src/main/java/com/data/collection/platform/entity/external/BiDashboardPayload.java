package com.data.collection.platform.entity.external;

import com.data.collection.platform.entity.statistics.SystemTestModuleFixRateSnapshot;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable read-only payload for the independent BI dashboard. */
public record BiDashboardPayload(
    String productVersion,
    List<String> availableProductVersions,
    List<String> testingPhases,
    QualityTargets qualityTargets,
    List<SystemTestModuleFixRateSnapshot.ModuleRow> moduleFixRates,
    ReviewDistributions reviewDistributions,
    BoardTable phaseFixes,
    BoardTable severityDistribution,
    BoardTable defectCauseDistribution,
    BoardTable delayedDefects,
    List<FixUserRow> fixUsers,
    CodeSubmissionTrend codeSubmissionTrend) {
  public BiDashboardPayload {
    availableProductVersions = availableProductVersions == null
        ? List.of() : List.copyOf(availableProductVersions);
    testingPhases = testingPhases == null ? List.of() : List.copyOf(testingPhases);
    moduleFixRates = moduleFixRates == null ? List.of() : List.copyOf(moduleFixRates);
    reviewDistributions = reviewDistributions == null ? ReviewDistributions.empty() : reviewDistributions;
    phaseFixes = phaseFixes == null ? BoardTable.empty("system-test-phase-statistics") : phaseFixes;
    severityDistribution = severityDistribution == null ? BoardTable.empty("system-test-severity") : severityDistribution;
    defectCauseDistribution = defectCauseDistribution == null ? BoardTable.empty("system-test-defect-causes") : defectCauseDistribution;
    delayedDefects = delayedDefects == null ? BoardTable.empty("system-test-delays") : delayedDefects;
    fixUsers = fixUsers == null ? List.of() : List.copyOf(fixUsers);
    codeSubmissionTrend = codeSubmissionTrend == null ? CodeSubmissionTrend.empty() : codeSubmissionTrend;
  }

  public record QualityTargets(List<QualityMetric> metrics) {
    public QualityTargets {
      metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
  }

  public record QualityMetric(
      String key,
      String label,
      BigDecimal value,
      String unit,
      String target,
      Boolean achieved) {}

  public record ReviewDistributions(
      List<ReviewTypeRow> byType,
      List<ReviewCategoryRow> byCategory,
      List<ReviewDensityRow> densities) {
    public ReviewDistributions {
      byType = byType == null ? List.of() : List.copyOf(byType);
      byCategory = byCategory == null ? List.of() : List.copyOf(byCategory);
      densities = densities == null ? List.of() : List.copyOf(densities);
    }

    public static ReviewDistributions empty() {
      return new ReviewDistributions(List.of(), List.of(), List.of());
    }
  }

  public record ReviewTypeRow(String reviewType, long problemCount, BigDecimal sharePercent) {}

  public record ReviewCategoryRow(
      String reviewType, String problemCategory, long problemCount, BigDecimal sharePercent) {}

  public record ReviewDensityRow(
      String reviewType,
      String moduleName,
      long problemCount,
      long reviewScalePages,
      BigDecimal density) {}

  public record BoardTable(
      String boardKey,
      String title,
      List<BoardColumn> columns,
      List<BoardRow> rows) {
    public BoardTable {
      columns = columns == null ? List.of() : List.copyOf(columns);
      rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static BoardTable empty(String boardKey) {
      return new BoardTable(boardKey, "", List.of(), List.of());
    }
  }

  public record BoardColumn(String key, String label, String metricType) {}

  public record BoardRow(String rowKey, String rowLabel, Map<String, BoardCell> cells) {
    public BoardRow {
      cells = cells == null
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(cells));
    }
  }

  public record BoardCell(long numericValue, String displayValue) {}

  public record FixUserRow(
      String fixUser,
      long level1Count,
      long level2Count,
      long level3Count,
      long fixedCount,
      long openCount,
      long totalCount) {}

  public record CodeSubmissionTrend(
      String granularity,
      List<CodeSubmissionPoint> points) {
    public CodeSubmissionTrend {
      points = points == null ? List.of() : List.copyOf(points);
    }

    public static CodeSubmissionTrend empty() {
      return new CodeSubmissionTrend("day", List.of());
    }
  }

  public record CodeSubmissionPoint(
      String source,
      String period,
      long newLines,
      long cumulativeLines,
      long mergeRequestCount) {}
}
