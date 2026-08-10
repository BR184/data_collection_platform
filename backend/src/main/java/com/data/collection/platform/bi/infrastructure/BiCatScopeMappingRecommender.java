package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.BiProductVersionMatcher;
import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiProductVersionOption;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogNode;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogProject;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogSnapshot;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** 根据 CAT 目录的明确标志和唯一语义生成待用户确认的稳定 ID 映射建议。 */
final class BiCatScopeMappingRecommender {
  private final BiProductVersionMatcher versionMatcher = new BiProductVersionMatcher();

  List<Suggestion> recommend(BiProductVersionCatalog productVersions, CatalogSnapshot catalog) {
    CatalogProject project = recommendedProject(catalog.projects());
    return productVersions.versions().stream()
        .map(version -> recommendVersion(productVersions.defaultVersionId(), version, project, catalog))
        .toList();
  }

  private Suggestion recommendVersion(
      Long defaultVersionId,
      BiProductVersionOption productVersion,
      CatalogProject project,
      CatalogSnapshot catalog) {
    if (project == null) {
      return Suggestion.empty(productVersion.id());
    }
    CatalogNode version = recommendedVersion(defaultVersionId, productVersion, project.id(), catalog);
    if (version == null) {
      return new Suggestion(productVersion.id(), project.id(), null, null, null);
    }
    List<CatalogNode> phases = catalog.nodes().stream()
        .filter(node -> "TEST_PHASE".equals(node.nodeType()))
        .filter(node -> project.id().equals(node.projectId()))
        .filter(node -> version.id().equals(node.versionId()))
        .toList();
    CatalogNode unit = unique(phases.stream().filter(this::isUnitTest).toList());
    CatalogNode integration = unique(phases.stream().filter(this::isIntegrationTest).toList());
    return new Suggestion(
        productVersion.id(),
        project.id(),
        version.id(),
        unit == null ? null : unit.id(),
        integration == null ? null : integration.id());
  }

  private CatalogProject recommendedProject(List<CatalogProject> projects) {
    List<CatalogProject> defaults = projects.stream()
        .filter(project -> Boolean.TRUE.equals(project.defaultProject()))
        .toList();
    if (!defaults.isEmpty()) {
      return unique(defaults);
    }
    return unique(projects);
  }

  private CatalogNode recommendedVersion(
      Long defaultVersionId,
      BiProductVersionOption productVersion,
      String projectId,
      CatalogSnapshot catalog) {
    List<CatalogNode> versions = catalog.nodes().stream()
        .filter(node -> "VERSION".equals(node.nodeType()))
        .filter(node -> projectId.equals(node.projectId()))
        .toList();
    List<CatalogNode> semanticMatches = versions.stream()
        .filter(node -> versionMatcher.matches(node.name(), productVersion.businessKey()))
        .toList();
    if (!semanticMatches.isEmpty()) {
      return unique(semanticMatches);
    }
    if (defaultVersionId == null || defaultVersionId != productVersion.id()) {
      return null;
    }
    return unique(versions.stream()
        .filter(node -> Boolean.TRUE.equals(node.currentVersion()))
        .toList());
  }

  private boolean isUnitTest(CatalogNode node) {
    String value = normalizedStageText(node);
    return value.contains("单元测试")
        || value.matches("^(?:UT|UNITTEST|UNITTESTING)(?:测试|阶段|测试阶段)?$");
  }

  private boolean isIntegrationTest(CatalogNode node) {
    String value = normalizedStageText(node);
    return value.contains("集成测试")
        || value.matches("^(?:IT|SIT|INTEGRATIONTEST|INTEGRATIONTESTING)(?:测试|阶段|测试阶段)?$");
  }

  private String normalizedStageText(CatalogNode node) {
    String source = node.name() + (node.note() == null ? "" : node.note());
    return Normalizer.normalize(source, Normalizer.Form.NFKC)
        .toUpperCase(Locale.ROOT)
        .replaceAll("[\\p{Punct}\\s_]", "");
  }

  private <T> T unique(List<T> values) {
    return values.size() == 1 ? values.getFirst() : null;
  }

  record Suggestion(
      long productVersionId,
      String catProjectId,
      String catVersionId,
      String unitTestingPhaseId,
      String integrationTestingPhaseId) {
    static Suggestion empty(long productVersionId) {
      return new Suggestion(productVersionId, null, null, null, null);
    }
  }
}
