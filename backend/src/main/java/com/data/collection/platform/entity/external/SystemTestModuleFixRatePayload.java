package com.data.collection.platform.entity.external;

import com.data.collection.platform.entity.statistics.SystemTestModuleFixRateSnapshot;
import java.util.List;

public record SystemTestModuleFixRatePayload(
    String productVersion,
    List<String> availableProductVersions,
    List<String> testingPhases,
    List<SystemTestModuleFixRateSnapshot.ModuleRow> modules) {
  public SystemTestModuleFixRatePayload {
    availableProductVersions = availableProductVersions == null
        ? List.of() : List.copyOf(availableProductVersions);
    testingPhases = testingPhases == null ? List.of() : List.copyOf(testingPhases);
    modules = modules == null ? List.of() : List.copyOf(modules);
  }
}
