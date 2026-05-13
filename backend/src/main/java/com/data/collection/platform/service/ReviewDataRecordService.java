package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataGitlabContextRefreshRequest;
import com.data.collection.platform.entity.ReviewDataGitlabContextRefreshResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import com.data.collection.platform.entity.ReviewDataSearchIndexBackfillResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataRecordService {
  private static final String GITLAB_CONTEXT_REFRESH_REASON = "review-data-gitlab-context";
  private static final List<String> MERGE_REQUEST_CONTEXT_TABLES =
      List.of(
          "merge_requests",
          "merge_request_metrics",
          "merge_request_reviewers",
          "merge_request_assignees",
          "projects",
          "users",
          "namespaces");
  private static final List<String> ISSUE_CONTEXT_TABLES =
      List.of("issues", "projects", "users", "labels", "label_links", "notes");

  private final ReviewDataRecordQueryService queryService;
  private final ReviewDataRecordCommandService commandService;
  private final ReviewDataFilterOptionService filterOptionService;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final GitlabMirrorSyncService gitlabMirrorSyncService;
  private final GitlabTableSyncPlanningService tableSyncPlanningService;

  public ReviewDataRecordService(
      ReviewDataRecordQueryService queryService,
      ReviewDataRecordCommandService commandService,
      ReviewDataFilterOptionService filterOptionService,
      ReviewDataRecordPersistenceSupport persistenceSupport,
      GitlabMirrorSyncService gitlabMirrorSyncService,
      GitlabTableSyncPlanningService tableSyncPlanningService) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.filterOptionService = filterOptionService;
    this.persistenceSupport = persistenceSupport;
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
    this.tableSyncPlanningService = tableSyncPlanningService;
  }

  public ReviewDataRecordListResponse listRecords(ReviewDataRecordQueryRequest request) {
    return queryService.listRecords(request);
  }

  public ReviewDataFilterOptionsResponse getFilterOptions() {
    return filterOptionService.getFilterOptions();
  }

  public ReviewDataRecordDetailResponse getRecordDetail(Long recordId) {
    return queryService.getRecordDetail(recordId);
  }

  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    return queryService.listProblemItems(recordId);
  }

  public ReviewDataRecordDetailResponse createRecord(ReviewDataRecordSaveRequest request) {
    Long recordId = commandService.createRecord(request);
    return queryService.getRecordDetail(recordId);
  }

  public ReviewDataRecordDetailResponse updateRecord(Long recordId, ReviewDataRecordSaveRequest request) {
    Long updatedRecordId = commandService.updateRecord(recordId, request);
    return queryService.getRecordDetail(updatedRecordId);
  }

  public void deleteRecord(Long recordId) {
    commandService.deleteRecord(recordId);
  }

  public ReviewDataProblemItemResponse createProblemItem(
      Long recordId, ReviewDataProblemItemSaveRequest request) {
    Long itemId = commandService.createProblemItem(recordId, request);
    return queryService.getProblemItem(recordId, itemId);
  }

  public ReviewDataProblemItemResponse updateProblemItem(
      Long recordId, Long itemId, ReviewDataProblemItemSaveRequest request) {
    Long updatedItemId = commandService.updateProblemItem(recordId, itemId, request);
    return queryService.getProblemItem(recordId, updatedItemId);
  }

  public void deleteProblemItem(Long recordId, Long itemId) {
    commandService.deleteProblemItem(recordId, itemId);
  }

  public ReviewDataSearchIndexBackfillResponse backfillMissingSearchIndexes(int batchSize) {
    int safeBatchSize = batchSize <= 0 ? 200 : Math.min(batchSize, 2000);
    persistenceSupport.refreshMissingSearchIndexes(safeBatchSize);
    return new ReviewDataSearchIndexBackfillResponse(
        safeBatchSize,
        persistenceSupport.hasMissingSearchIndexes(),
        persistenceSupport.hasMissingTitleSearchIndexes());
  }

  public ReviewDataGitlabContextRefreshResponse refreshGitlabContext(
      ReviewDataGitlabContextRefreshRequest request) {
    List<String> resourceTypes = resolveResourceTypes(request);
    if (resourceTypes.isEmpty()) {
      return new ReviewDataGitlabContextRefreshResponse(
          false,
          null,
          "SKIPPED",
          List.of(),
          List.of(),
          0,
          false,
          "当前评审记录没有关联 GitLab 上下文，仅需刷新本地列表");
    }

    List<String> sourceTables = sourceTablesForResourceTypes(resourceTypes);
    GitlabMirrorSyncService.OnDemandRefreshResult result =
        gitlabMirrorSyncService.refreshTablesOnDemandDetailed(sourceTables, GITLAB_CONTEXT_REFRESH_REASON);
    return new ReviewDataGitlabContextRefreshResponse(
        true,
        result.jobId(),
        result.status().name(),
        resourceTypes,
        result.sourceTables(),
        result.plannedTasks(),
        false,
        "已开始同步关联 GitLab 上下文，不会覆盖人工评审字段");
  }

  public ReviewDataGitlabContextRefreshResponse getGitlabContextRefreshStatus(Long jobId) {
    var job = tableSyncPlanningService.findJob(jobId);
    if (job == null) {
      return new ReviewDataGitlabContextRefreshResponse(
          false,
          jobId,
          "MISSING",
          List.of(),
          List.of(),
          0,
          false,
          "GitLab 上下文刷新任务不存在");
    }
    var tasks = tableSyncPlanningService.listTasksForJob(jobId);
    return new ReviewDataGitlabContextRefreshResponse(
        true,
        jobId,
        job.getStatus() == null ? "PENDING" : job.getStatus().name(),
        List.of(),
        tasks.stream()
            .map(task -> task.getSourceTable())
            .filter(table -> table != null && !table.isBlank())
            .distinct()
            .toList(),
        tasks.size(),
        false,
        "GitLab 上下文刷新状态已更新");
  }

  private List<String> resolveResourceTypes(ReviewDataGitlabContextRefreshRequest request) {
    Set<String> resourceTypes = new LinkedHashSet<>();
    if (request != null && request.resourceType() != null && !request.resourceType().isBlank()) {
      addSupportedResourceType(resourceTypes, request.resourceType());
    }
    if (request != null) {
      for (Long recordId : request.recordIds()) {
        if (recordId == null) {
          continue;
        }
        ReviewDataRecordRowResponse row = queryService.getRecordDetail(recordId).record();
        if (hasGitlabContext(row)) {
          addSupportedResourceType(resourceTypes, row.gitlabResourceType());
        }
      }
    }
    return List.copyOf(resourceTypes);
  }

  private boolean hasGitlabContext(ReviewDataRecordRowResponse row) {
    return row != null
        && row.gitlabProjectId() != null
        && row.gitlabResourceIid() != null
        && row.gitlabResourceType() != null
        && !row.gitlabResourceType().isBlank();
  }

  private void addSupportedResourceType(Set<String> resourceTypes, String rawResourceType) {
    String resourceType = rawResourceType.trim().toLowerCase(Locale.ROOT);
    if ("merge_request".equals(resourceType) || "issue".equals(resourceType)) {
      resourceTypes.add(resourceType);
    }
  }

  private List<String> sourceTablesForResourceTypes(List<String> resourceTypes) {
    Set<String> sourceTables = new LinkedHashSet<>();
    for (String resourceType : resourceTypes) {
      if ("merge_request".equals(resourceType)) {
        sourceTables.addAll(MERGE_REQUEST_CONTEXT_TABLES);
      }
      if ("issue".equals(resourceType)) {
        sourceTables.addAll(ISSUE_CONTEXT_TABLES);
      }
    }
    return List.copyOf(sourceTables);
  }
}
