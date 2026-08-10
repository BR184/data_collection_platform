package com.data.collection.platform.bi.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** CAT 镜像内部使用的不可变配置、目录、快照和运行模型。 */
final class BiCatMirrorModels {
  private BiCatMirrorModels() {}

  record Config(
      boolean enabled,
      String baseUrl,
      boolean autoSyncEnabled,
      int syncIntervalMinutes,
      boolean fullCompensationEnabled,
      LocalTime fullCompensationTime) {}

  record ScopeMapping(
      long productVersionId,
      String productVersionKey,
      String catProjectId,
      String catVersionId,
      String unitTestingPhaseId,
      String integrationTestingPhaseId) {
    String testingPhaseId(String testStage) {
      return "UNIT_TEST".equals(testStage)
          ? unitTestingPhaseId
          : integrationTestingPhaseId;
    }
  }

  record CatalogProject(
      String id,
      String name,
      String note,
      String createTime,
      String createUserId,
      Boolean defaultProject) {}

  record CatalogNode(
      String projectId,
      String nodeType,
      String id,
      String parentId,
      String name,
      String createTime,
      String note,
      String endTime,
      Boolean currentVersion,
      Boolean disabled,
      Boolean defaultProject,
      String groupId,
      String versionId) {}

  record RawResponse(
      String operation,
      String requestKey,
      String requestPayload,
      String responsePayload) {}

  record CatalogSnapshot(
      UUID snapshotId,
      Instant collectedAt,
      List<CatalogProject> projects,
      List<CatalogNode> nodes,
      List<RawResponse> rawResponses) {
    CatalogSnapshot {
      projects = List.copyOf(projects);
      nodes = List.copyOf(nodes);
      rawResponses = List.copyOf(rawResponses);
    }
  }

  record ModuleSnapshot(
      String id,
      String name,
      long attainedFunctionCount,
      long totalFunctionCount,
      BigDecimal passRate,
      int displayOrder) {}

  record FunctionSnapshot(
      String moduleId,
      String id,
      String name,
      String label,
      BigDecimal passRate,
      int displayOrder) {}

  record TestSnapshot(
      UUID snapshotId,
      ScopeMapping mapping,
      String testStage,
      String testingPhaseId,
      String dataStatus,
      BigDecimal overallPassRate,
      Long attainedFunctionCount,
      Long totalFunctionCount,
      Instant collectionStartedAt,
      Instant collectionFinishedAt,
      List<ModuleSnapshot> modules,
      List<FunctionSnapshot> functions,
      List<RawResponse> rawResponses) {
    TestSnapshot {
      modules = List.copyOf(modules);
      functions = List.copyOf(functions);
      rawResponses = List.copyOf(rawResponses);
    }
  }

  record Run(
      UUID runId,
      String runType,
      String triggerType,
      String status,
      Instant startedAt,
      Instant finishedAt,
      UUID catalogSnapshotId,
      int publishedStageCount,
      int failedStageCount,
      String message) {}

  record PublishedTestSnapshot(
      UUID snapshotId,
      long publishedVersion,
      String dataStatus,
      BigDecimal overallPassRate,
      Long attainedFunctionCount,
      Long totalFunctionCount,
      List<ModuleSnapshot> modules,
      List<FunctionSnapshot> functions) {
    PublishedTestSnapshot {
      modules = List.copyOf(modules);
      functions = List.copyOf(functions);
    }
  }
}
