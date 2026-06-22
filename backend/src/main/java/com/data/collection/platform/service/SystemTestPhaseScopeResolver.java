package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestPhaseScopeResolver {
  private final SystemTestPhaseCatalogService phaseCatalogService;

  public SystemTestPhaseScopeResolver(SystemTestPhaseCatalogService phaseCatalogService) {
    this.phaseCatalogService = phaseCatalogService;
  }

  public List<String> resolveLegacyCrownCadPhases(String phaseOrParent) {
    return resolvePhases(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, phaseOrParent);
  }

  public List<String> resolveLegacyCrownCadPhases(List<String> phaseOrParents) {
    return resolvePhases(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, phaseOrParents);
  }

  public List<String> resolvePhases(Long projectId, String phaseOrParent) {
    String normalized = TextQuerySupport.trimToNull(phaseOrParent);
    if (normalized == null) {
      return List.of();
    }
    List<String> configuredPhases = phaseCatalogService.listTestingPhasesByParent(projectId, normalized);
    return configuredPhases.isEmpty() ? List.of(normalized) : configuredPhases;
  }

  public List<String> resolvePhases(Long projectId, List<String> phaseOrParents) {
    if (phaseOrParents == null || phaseOrParents.isEmpty()) {
      return List.of();
    }
    Set<String> resolved = new LinkedHashSet<>();
    for (String value : phaseOrParents) {
      resolved.addAll(resolvePhases(projectId, value));
    }
    return List.copyOf(resolved);
  }

  public boolean matchesLegacyCrownCadPhase(String actualTestingPhase, String selectedPhaseOrParent) {
    String actual = TextQuerySupport.trimToNull(actualTestingPhase);
    String selected = TextQuerySupport.trimToNull(selectedPhaseOrParent);
    if (selected == null) {
      return true;
    }
    if (actual == null) {
      return false;
    }
    return resolveLegacyCrownCadPhases(selected).stream()
        .anyMatch(phase -> actual.equalsIgnoreCase(phase));
  }

  public boolean matchesLegacyCrownCadPhases(String actualTestingPhase, List<String> selectedPhaseOrParents) {
    if (selectedPhaseOrParents == null || selectedPhaseOrParents.isEmpty()) {
      return true;
    }
    String actual = TextQuerySupport.trimToNull(actualTestingPhase);
    if (actual == null) {
      return false;
    }
    return resolveLegacyCrownCadPhases(selectedPhaseOrParents).stream()
        .filter(StringUtils::hasText)
        .anyMatch(phase -> actual.equalsIgnoreCase(phase));
  }
}
