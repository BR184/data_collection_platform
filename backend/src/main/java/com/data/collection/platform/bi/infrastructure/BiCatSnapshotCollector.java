package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.FeaturePage;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.FeatureStatistic;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.ModuleStatistic;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.PhaseNode;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.Project;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.StatisticsPayload;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogNode;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogProject;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.FunctionSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ModuleSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.RawResponse;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ScopeMapping;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.TestSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 采集并校验 CAT 目录和单个测试阶段，成功结果才允许交给仓储发布。 */
final class BiCatSnapshotCollector {
  private final Clock clock;

  BiCatSnapshotCollector(Clock clock) {
    this.clock = clock;
  }

  CatalogSnapshot collectCatalog(BiCatHttpClient client) {
    var projectResponse = client.projects();
    List<Project> sourceProjects = safeList(projectResponse.data());
    List<CatalogProject> projects = new ArrayList<>();
    List<CatalogNode> nodes = new ArrayList<>();
    List<RawResponse> rawResponses = new ArrayList<>();
    Set<String> projectIds = new HashSet<>();
    rawResponses.add(new RawResponse(
        "PROJECTS", "", null, projectResponse.rawJson()));
    for (Project source : sourceProjects) {
      if (source == null) {
        throw unavailable("CAT 项目目录包含空记录");
      }
      String projectId = requireText(source.id(), "CAT 项目 ID");
      if (!projectIds.add(projectId)) {
        throw unavailable("CAT 项目目录返回重复项目 ID");
      }
      projects.add(new CatalogProject(
          projectId,
          requireText(source.name(), "CAT 项目名称"),
          normalizeNullable(source.note()),
          normalizeNullable(source.createTime()),
          normalizeNullable(source.createUserId()),
          source.defaultProject()));
      var treeResponse = client.phaseTree(projectId);
      rawResponses.add(new RawResponse(
          "PHASE_TREE",
          projectId,
          "{\"projectId\":\"" + jsonString(projectId) + "\"}",
          treeResponse.rawJson()));
      collectProjectNodes(projectId, safeList(treeResponse.data()), nodes);
    }
    return new CatalogSnapshot(
        UUID.randomUUID(), clock.instant(), projects, nodes, rawResponses);
  }

  TestSnapshot collectStage(
      BiCatHttpClient client,
      ScopeMapping mapping,
      String testStage,
      CatalogSnapshot catalog) {
    String testingPhaseId = requireText(
        mapping.testingPhaseId(testStage), "CAT 测试阶段 ID");
    validateMappedStage(catalog, mapping, testingPhaseId);
    Instant startedAt = clock.instant();
    var statisticsResponse = client.statistics(testingPhaseId);
    StatisticsPayload statistics = statisticsResponse.data();
    List<RawResponse> rawResponses = new ArrayList<>();
    rawResponses.add(new RawResponse(
        "STATISTICS",
        testingPhaseId,
        "{\"testingPhaseId\":\"" + jsonString(testingPhaseId) + "\"}",
        statisticsResponse.rawJson()));
    if (statistics == null || statistics.result() == null || statistics.result().isEmpty()) {
      return new TestSnapshot(
          UUID.randomUUID(),
          mapping,
          testStage,
          testingPhaseId,
          "EMPTY",
          null,
          null,
          null,
          startedAt,
          clock.instant(),
          List.of(),
          List.of(),
          rawResponses);
    }

    BigDecimal overallRate = requireRate(statistics.passRate(), "CAT 整体通过率");
    List<ModuleSnapshot> modules = new ArrayList<>();
    List<FunctionSnapshot> functions = new ArrayList<>();
    Set<String> moduleIds = new HashSet<>();
    long attainedTotal = 0;
    long functionTotal = 0;
    int moduleOrder = 0;
    for (ModuleStatistic sourceModule : statistics.result()) {
      ModuleSnapshot module = validateModule(sourceModule, moduleIds, moduleOrder++);
      modules.add(module);
      attainedTotal = addExact(
          attainedTotal, module.attainedFunctionCount(), "CAT 整体达标功能数");
      functionTotal = addExact(
          functionTotal, module.totalFunctionCount(), "CAT 整体统计功能数");
      var featureResponse = client.features(module.id(), testingPhaseId);
      rawResponses.add(new RawResponse(
          "FEATURES",
          module.id(),
          "{\"moduleId\":\"" + jsonString(module.id())
              + "\",\"testingPhaseId\":\"" + jsonString(testingPhaseId) + "\"}",
          featureResponse.rawJson()));
      functions.addAll(validateFunctions(featureResponse.data(), module.id()));
    }
    return new TestSnapshot(
        UUID.randomUUID(),
        mapping,
        testStage,
        testingPhaseId,
        "READY",
        overallRate,
        attainedTotal,
        functionTotal,
        startedAt,
        clock.instant(),
        modules,
        functions,
        rawResponses);
  }

  private void collectProjectNodes(
      String projectId,
      List<PhaseNode> versions,
      List<CatalogNode> target) {
    Set<String> versionIds = new HashSet<>();
    Set<String> phaseIds = new HashSet<>();
    for (PhaseNode version : versions) {
      if (version == null) {
        throw unavailable("CAT 版本目录包含空记录");
      }
      String versionId = requireText(version.id(), "CAT 版本 ID");
      if (!versionIds.add(versionId)) {
        throw unavailable("CAT 同一项目返回重复版本 ID");
      }
      target.add(toCatalogNode(projectId, "VERSION", null, version));
      collectPhaseNodes(
          projectId,
          versionId,
          versionId,
          safeList(version.children()),
          phaseIds,
          target);
    }
  }

  private void collectPhaseNodes(
      String projectId,
      String versionId,
      String parentId,
      List<PhaseNode> phases,
      Set<String> phaseIds,
      List<CatalogNode> target) {
    for (PhaseNode phase : phases) {
      if (phase == null) {
        throw unavailable("CAT 测试阶段目录包含空记录");
      }
      String phaseId = requireText(phase.id(), "CAT 测试阶段 ID");
      if (!phaseIds.add(phaseId)) {
        throw unavailable("CAT 同一项目返回重复测试阶段 ID");
      }
      if (!projectId.equals(requireText(phase.projectId(), "CAT 测试阶段项目 ID"))
          || !versionId.equals(requireText(phase.versionId(), "CAT 测试阶段版本 ID"))) {
        throw unavailable("CAT 测试阶段与所属项目或版本不一致");
      }
      target.add(toCatalogNode(projectId, "TEST_PHASE", parentId, phase));
      collectPhaseNodes(
          projectId,
          versionId,
          phaseId,
          safeList(phase.children()),
          phaseIds,
          target);
    }
  }

  private CatalogNode toCatalogNode(
      String projectId,
      String nodeType,
      String parentId,
      PhaseNode source) {
    return new CatalogNode(
        projectId,
        nodeType,
        requireText(source.id(), "CAT 目录节点 ID"),
        parentId,
        requireText(source.name(), "CAT 目录节点名称"),
        normalizeNullable(source.createTime()),
        normalizeNullable(source.note()),
        normalizeNullable(source.endTime()),
        source.curVersion(),
        source.disabled(),
        source.defaultProject(),
        normalizeNullable(source.groupId()),
        normalizeNullable(source.versionId()));
  }

  private void validateMappedStage(
      CatalogSnapshot catalog,
      ScopeMapping mapping,
      String testingPhaseId) {
    boolean mapped = catalog.nodes().stream().anyMatch(node ->
        "TEST_PHASE".equals(node.nodeType())
            && mapping.catProjectId().equals(node.projectId())
            && mapping.catVersionId().equals(node.versionId())
            && testingPhaseId.equals(node.id()));
    if (!mapped) {
      throw unavailable("CAT 产品版本映射与本次目录不一致");
    }
  }

  private ModuleSnapshot validateModule(
      ModuleStatistic source,
      Set<String> moduleIds,
      int displayOrder) {
    if (source == null) {
      throw unavailable("CAT 模块统计包含空记录");
    }
    String moduleId = requireText(source.id(), "CAT 模块 ID");
    if (!moduleIds.add(moduleId)) {
      throw unavailable("CAT 模块统计返回重复模块 ID");
    }
    String moduleName = requireText(source.moduleName(), "CAT 模块名称");
    if (!moduleName.equals(requireText(source.name(), "CAT 模块冗余名称"))) {
      throw unavailable("CAT 模块名称字段不一致");
    }
    long attained = requireCount(source.passFeatureCount(), "CAT 达标功能数");
    long notAttained = requireCount(source.notPassFeatureCount(), "CAT 未达标功能数");
    return new ModuleSnapshot(
        moduleId,
        moduleName,
        attained,
        addExact(attained, notAttained, "CAT 模块统计功能数"),
        requireRate(source.testPassRate(), "CAT 模块通过率"),
        displayOrder);
  }

  private List<FunctionSnapshot> validateFunctions(FeaturePage page, String moduleId) {
    if (page == null || page.statisticsInfoList() == null) {
      throw unavailable("CAT 功能全量响应缺少明细列表");
    }
    if (page.totalCount() == null || page.totalCount() != page.statisticsInfoList().size()) {
      throw unavailable("CAT 功能全量响应的 totalCount 与明细数量不一致");
    }
    Set<String> functionIds = new HashSet<>();
    List<FunctionSnapshot> result = new ArrayList<>();
    int displayOrder = 0;
    for (FeatureStatistic source : page.statisticsInfoList()) {
      if (source == null) {
        throw unavailable("CAT 功能统计包含空记录");
      }
      String functionId = requireText(source.featureUniqueId(), "CAT 功能 ID");
      if (!functionIds.add(functionId)) {
        throw unavailable("CAT 同一模块返回重复功能 ID");
      }
      result.add(new FunctionSnapshot(
          moduleId,
          functionId,
          requireText(source.name(), "CAT 功能名称"),
          normalizeNullable(source.featureLabel()),
          requireRate(source.testPassRate(), "CAT 功能通过率"),
          displayOrder++));
    }
    return List.copyOf(result);
  }

  private BigDecimal requireRate(BigDecimal value, String label) {
    if (value == null
        || value.compareTo(BigDecimal.ZERO) < 0
        || value.compareTo(new BigDecimal("100")) > 0) {
      throw unavailable(label + "必须在 0 至 100 之间");
    }
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private long requireCount(Long value, String label) {
    if (value == null || value < 0) {
      throw unavailable(label + "必须是非负整数");
    }
    return value;
  }

  private long addExact(long left, long right, String label) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      throw unavailable(label + "超出长整数范围");
    }
  }

  private String requireText(String value, String label) {
    String normalized = normalizeNullable(value);
    if (normalized == null) {
      throw unavailable(label + "不能为空");
    }
    return normalized;
  }

  private String normalizeNullable(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String jsonString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private <T> List<T> safeList(List<T> values) {
    return values == null ? List.of() : values;
  }

  private BiCatContractUnavailableException unavailable(String message) {
    return new BiCatContractUnavailableException(message);
  }
}
