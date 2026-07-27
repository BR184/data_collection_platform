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

  /** 返回指定项目按管理员顺序排列的启用版本业务键。 */
  public List<String> listEnabledParentNames(Long projectId) {
    return phaseCatalogService.listParentNames(projectId);
  }

  /** 返回指定项目的默认版本业务键；没有启用目录时返回空字符串。 */
  public String defaultParentName(Long projectId) {
    return listEnabledParentNames(projectId).stream().findFirst().orElse("");
  }

  public List<String> resolvePhases(Long projectId, String phaseOrParent) {
    String normalized = TextQuerySupport.trimToNull(phaseOrParent);
    if (normalized == null) {
      return List.of();
    }
    List<String> configuredPhases = phaseCatalogService.listTestingPhasesByParent(projectId, normalized);
    if (!configuredPhases.isEmpty()) {
      return configuredPhases;
    }
    return phaseCatalogService.isConfiguredTestingPhase(projectId, normalized)
        ? List.of(normalized)
        : List.of();
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

  public boolean matchesPhase(
      Long projectId, String actualTestingPhase, String selectedPhaseOrParent) {
    String actual = TextQuerySupport.trimToNull(actualTestingPhase);
    String selected = TextQuerySupport.trimToNull(selectedPhaseOrParent);
    if (selected == null) {
      return true;
    }
    if (actual == null) {
      return false;
    }
    return resolvePhases(projectId, selected).stream()
        .anyMatch(phase -> actual.equalsIgnoreCase(phase));
  }

  public boolean matchesPhases(
      Long projectId, String actualTestingPhase, List<String> selectedPhaseOrParents) {
    if (selectedPhaseOrParents == null || selectedPhaseOrParents.isEmpty()) {
      return true;
    }
    String actual = TextQuerySupport.trimToNull(actualTestingPhase);
    if (actual == null) {
      return false;
    }
    return resolvePhases(projectId, selectedPhaseOrParents).stream()
        .filter(StringUtils::hasText)
        .anyMatch(phase -> actual.equalsIgnoreCase(phase));
  }
}
