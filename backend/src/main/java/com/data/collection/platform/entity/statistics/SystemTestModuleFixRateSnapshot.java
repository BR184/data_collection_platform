package com.data.collection.platform.entity.statistics;

import java.math.BigDecimal;
import java.util.List;

public record SystemTestModuleFixRateSnapshot(
    String productVersion,
    List<String> testingPhases,
    List<ModuleRow> modules) {
  public SystemTestModuleFixRateSnapshot {
    testingPhases = testingPhases == null ? List.of() : List.copyOf(testingPhases);
    modules = modules == null ? List.of() : List.copyOf(modules);
  }

  public record ModuleRow(
      String moduleName,
      FixRateMetric overall,
      FixRateMetric level1,
      FixRateMetric p1,
      FixRateMetric p2) {
  }

  public record FixRateMetric(
      long defectCount,
      long fixedCount,
      BigDecimal fixRatePercent) {
  }
}
