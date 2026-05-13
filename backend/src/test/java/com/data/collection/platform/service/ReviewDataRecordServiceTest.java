package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataGitlabContextRefreshRequest;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import com.data.collection.platform.entity.SyncStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataRecordServiceTest {
  @Mock private ReviewDataRecordQueryService queryService;
  @Mock private ReviewDataRecordCommandService commandService;
  @Mock private ReviewDataFilterOptionService filterOptionService;
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;
  @Mock private GitlabMirrorSyncService gitlabMirrorSyncService;
  @Mock private GitlabTableSyncPlanningService tableSyncPlanningService;

  @Test
  void shouldComposeCreateRecordCommandWithDetailQuery() {
    ReviewDataRecordService service =
        service();
    ReviewDataRecordSaveRequest request = recordRequest();
    ReviewDataRecordDetailResponse detail = detail(10L);

    when(commandService.createRecord(request)).thenReturn(10L);
    when(queryService.getRecordDetail(10L)).thenReturn(detail);

    assertThat(service.createRecord(request)).isSameAs(detail);
    verify(commandService).createRecord(request);
    verify(queryService).getRecordDetail(10L);
  }

  @Test
  void shouldComposeCreateProblemItemCommandWithItemQuery() {
    ReviewDataRecordService service =
        service();
    ReviewDataProblemItemSaveRequest request = problemRequest();
    ReviewDataProblemItemResponse item =
        new ReviewDataProblemItemResponse(
            20L,
            10L,
            "Expert A",
            1.5,
            "Walkthrough",
            "Section 1",
            "Completeness",
            "Missing field",
            "Add field",
            "Owner A",
            "",
            "Open",
            LocalDateTime.of(2026, 4, 27, 10, 0));

    when(commandService.createProblemItem(10L, request)).thenReturn(20L);
    when(queryService.getProblemItem(10L, 20L)).thenReturn(item);

    assertThat(service.createProblemItem(10L, request)).isSameAs(item);
    verify(commandService).createProblemItem(10L, request);
    verify(queryService).getProblemItem(10L, 20L);
  }

  @Test
  void shouldSkipGitlabContextRefreshWhenSelectedRecordsHaveNoGitlabLinkage() {
    ReviewDataRecordService service = service();
    when(queryService.getRecordDetail(10L)).thenReturn(detail(10L));

    var response = service.refreshGitlabContext(new ReviewDataGitlabContextRefreshRequest(List.of(10L), null));

    assertThat(response.accepted()).isFalse();
    assertThat(response.plannedTasks()).isZero();
    assertThat(response.manualFieldsTouched()).isFalse();
    assertThat(response.message()).contains("没有关联 GitLab 上下文");
  }

  @Test
  void shouldPlanOnlyMergeRequestContextTablesForMergeRequestRecords() {
    ReviewDataRecordService service = service();
    when(queryService.getRecordDetail(10L)).thenReturn(detailWithGitlabContext(10L, "merge_request"));
    when(gitlabMirrorSyncService.refreshTablesOnDemandDetailed(anyList(), eq("review-data-gitlab-context")))
        .thenReturn(new GitlabMirrorSyncService.OnDemandRefreshResult(
            88L,
            List.of("merge_requests", "merge_request_metrics", "projects", "users", "namespaces"),
            5,
            List.of(),
            SyncStatus.SUCCESS));

    var response = service.refreshGitlabContext(new ReviewDataGitlabContextRefreshRequest(List.of(10L), null));

    assertThat(response.accepted()).isTrue();
    assertThat(response.jobId()).isEqualTo(88L);
    assertThat(response.resourceTypes()).containsExactly("merge_request");
    assertThat(response.sourceTables()).contains("merge_requests", "merge_request_metrics");
    assertThat(response.sourceTables()).doesNotContain("issues", "notes");
    assertThat(response.manualFieldsTouched()).isFalse();
    verify(gitlabMirrorSyncService)
        .refreshTablesOnDemandDetailed(
            List.of(
                "merge_requests",
                "merge_request_metrics",
                "merge_request_reviewers",
                "merge_request_assignees",
                "projects",
                "users",
                "namespaces"),
            "review-data-gitlab-context");
  }

  @Test
  void shouldPlanOnlyIssueContextTablesForIssueRecords() {
    ReviewDataRecordService service = service();
    when(queryService.getRecordDetail(11L)).thenReturn(detailWithGitlabContext(11L, "issue"));
    when(gitlabMirrorSyncService.refreshTablesOnDemandDetailed(anyList(), eq("review-data-gitlab-context")))
        .thenReturn(new GitlabMirrorSyncService.OnDemandRefreshResult(
            89L,
            List.of("issues", "projects", "users", "labels", "label_links", "notes"),
            6,
            List.of(),
            SyncStatus.SUCCESS));

    var response = service.refreshGitlabContext(new ReviewDataGitlabContextRefreshRequest(List.of(11L), null));

    assertThat(response.accepted()).isTrue();
    assertThat(response.resourceTypes()).containsExactly("issue");
    assertThat(response.sourceTables()).contains("issues", "notes");
    assertThat(response.sourceTables()).doesNotContain("merge_requests");
  }

  private ReviewDataRecordService service() {
    return new ReviewDataRecordService(
        queryService,
        commandService,
        filterOptionService,
        persistenceSupport,
        gitlabMirrorSyncService,
        tableSyncPlanningService);
  }

  private ReviewDataRecordSaveRequest recordRequest() {
    return new ReviewDataRecordSaveRequest(
        "Project A",
        "Review A",
        "Module A",
        "Design review",
        LocalDate.of(2026, 4, 27),
        "Owner A",
        List.of("Expert A"),
        12,
        "Spec",
        "Author A",
        "v1");
  }

  private ReviewDataProblemItemSaveRequest problemRequest() {
    return new ReviewDataProblemItemSaveRequest(
        "Expert A",
        1.5,
        "Walkthrough",
        "Section 1",
        "Completeness",
        "Missing field",
        "Add field",
        "Owner A",
        "",
        "Open");
  }

  private ReviewDataRecordDetailResponse detail(Long recordId) {
    return new ReviewDataRecordDetailResponse(
        new ReviewDataRecordRowResponse(
            recordId,
            "Project A",
            "Review A",
            "Module A",
            "Design review",
            LocalDate.of(2026, 4, 27),
            "Owner A",
            "Expert A",
            12,
            "Spec",
            "Author A",
            "v1",
            0,
            0D,
            LocalDateTime.of(2026, 4, 27, 10, 0),
            false),
        List.of("Expert A"),
        List.of());
  }

  private ReviewDataRecordDetailResponse detailWithGitlabContext(Long recordId, String resourceType) {
    return new ReviewDataRecordDetailResponse(
        new ReviewDataRecordRowResponse(
            recordId,
            "Project A",
            "Review A",
            "Module A",
            "Design review",
            LocalDate.of(2026, 4, 27),
            "Owner A",
            "Expert A",
            12,
            "Spec",
            "Author A",
            "v1",
            0,
            0D,
            LocalDateTime.of(2026, 4, 27, 10, 0),
            false,
            325L,
            12L,
            resourceType),
        List.of("Expert A"),
        List.of());
  }
}
