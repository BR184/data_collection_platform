package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.SyncStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CodeReviewIllegalRecordServiceTest {

  @Mock private GitlabMirrorSyncService gitlabMirrorSyncService;
  @Mock private RealtimeWorkspaceService realtimeWorkspaceService;
  @Mock private FactBuildService factBuildService;
  @Mock private CodeReviewIllegalRecordSourceLoader sourceLoader;

  private CodeReviewIllegalRecordService service;

  @BeforeEach
  void setUp() {
    GitlabMirrorProperties gitlabMirrorProperties = new GitlabMirrorProperties();
    gitlabMirrorProperties.setWebBaseUrl("http://gitlab.example.com");
    service =
        new CodeReviewIllegalRecordService(
            gitlabMirrorSyncService,
            realtimeWorkspaceService,
            factBuildService,
            sourceLoader,
            new ObjectMapper(),
            gitlabMirrorProperties);
  }

  @Test
  void shouldKeepListRecordsBehaviorAfterRefactor() {
    when(sourceLoader.loadDefaultIllegalPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    source(
                        102L,
                        5,
                        "repo-a",
                        "Bob",
                        "Owner B",
                        "",
                        LocalDateTime.of(2026, 4, 9, 10, 0)),
                    source(
                        101L,
                        12,
                        "repo-b",
                        "Alice",
                        "Owner A",
                        "",
                        LocalDateTime.of(2026, 4, 8, 10, 0))),
                2,
                1,
                20));

    CodeReviewIllegalRecordListResponse response =
        service.listRecords(
            new CodeReviewIllegalRecordQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                "merge_request",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                20,
                "mergeRequestIid",
                "asc",
                null));

    assertThat(response.total()).isEqualTo(2);
    assertThat(response.sortField()).isEqualTo("mergeRequestIid");
    assertThat(response.sortOrder()).isEqualTo("asc");
    assertThat(response.records()).extracting(item -> item.mergeRequestIid()).containsExactly(5, 12);
    assertThat(response.records()).allMatch(item -> item.mergeRequestLink() != null);
  }

  @Test
  void shouldBuildFilterOptionsFromLoadedSources() {
    when(sourceLoader.loadSources(anyMap()))
        .thenReturn(
            List.of(
                source(101L, 12, "repo-b", "Alice", "Owner A", "module-b", LocalDateTime.of(2026, 4, 8, 10, 0)),
                source(102L, 5, "repo-a", "Bob", "Owner B", "module-a", LocalDateTime.of(2026, 4, 9, 10, 0))));

    CodeReviewIllegalRecordFilterOptionsResponse response =
        service.getFilterOptions(new CodeReviewIllegalRecordFilterOptionsRequest(null, "cc"));

    assertThat(response.requestTypes()).hasSize(1);
    assertThat(response.repositoryNames()).extracting(item -> item.value()).containsExactly("repo-a", "repo-b");
    assertThat(response.mergedBys()).extracting(item -> item.value()).containsExactly("Alice", "Bob");
    assertThat(response.moduleNames()).extracting(item -> item.value()).containsExactly("module-a", "module-b");
  }

  @Test
  void shouldPassFilterGroupToSqlPageAfterRefactor() {
    when(sourceLoader.loadDefaultIllegalPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(source(102L, 5, "repo-a", "Bob", "Owner B", "", LocalDateTime.of(2026, 4, 9, 10, 0))),
                1,
                1,
                20));

    CodeReviewIllegalRecordListResponse response =
        service.listRecords(
            new CodeReviewIllegalRecordQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                "merge_request",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"owner\",\"operator\":\"eq\",\"value\":\"Owner B\"}]}",
                1,
                20,
                "mergeRequestIid",
                "asc",
                null));

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.records()).extracting(item -> item.mergeRequestIid()).containsExactly(5);
    verify(sourceLoader)
        .loadDefaultIllegalPage(
            argThat(query -> query.filterGroup() != null && query.request().filterGroupJson() != null));
  }

  @Test
  void shouldPassKeywordNotContainsFilterGroupToSqlPage() {
    when(sourceLoader.loadDefaultIllegalPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    source(
                        102L,
                        5,
                        "repo-a",
                        "Bob",
                        "Owner B",
                        "",
                        LocalDateTime.of(2026, 4, 9, 10, 0),
                        "login api cleanup")),
                1,
                1,
                20));

    CodeReviewIllegalRecordListResponse response =
        service.listRecords(
            new CodeReviewIllegalRecordQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                "merge_request",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"keyword\",\"operator\":\"notContains\",\"value\":\"payment\"}]}",
                1,
                20,
                "mergeRequestIid",
                "asc",
                null));

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.records()).extracting(item -> item.mergeRequestContent()).containsExactly("login api cleanup");
    verify(sourceLoader)
        .loadDefaultIllegalPage(
            argThat(query -> query.filterGroup() != null && query.request().filterGroupJson() != null));
  }

  @Test
  void shouldPropagateRealtimeRefreshFailureToWorkspaceStatus() {
    RuntimeException failure = new RuntimeException("mirror refresh failed");
    when(gitlabMirrorSyncService.refreshTablesOnDemandDetailed(
            anyList(), eq(CodeReviewIllegalRecordService.WORKSPACE_KEY)))
        .thenThrow(failure);

    service.requestRealtimeRefresh();

    ArgumentCaptor<Supplier<RealtimeWorkspaceRefreshResult>> refreshAction =
        ArgumentCaptor.forClass(Supplier.class);
    verify(realtimeWorkspaceService)
        .requestRefreshWithResult(eq(CodeReviewIllegalRecordService.WORKSPACE_KEY), refreshAction.capture());
    assertThatThrownBy(refreshAction.getValue()::get).isSameAs(failure);
    verify(factBuildService, never()).rebuildMergeRequestFacts(false);
  }

  @Test
  void shouldReturnStructuredRealtimeRefreshResult() {
    when(gitlabMirrorSyncService.refreshTablesOnDemandDetailed(
            anyList(), eq(CodeReviewIllegalRecordService.WORKSPACE_KEY)))
        .thenReturn(new GitlabMirrorSyncService.OnDemandRefreshResult(
            31L, List.of("merge_requests"), 1, List.of("label_links"), SyncStatus.SUCCESS));
    when(factBuildService.rebuildMergeRequestFacts(false))
        .thenReturn(new FactBuildResponse("merge-request", false, 9, "fact ok"));

    service.requestRealtimeRefresh();

    ArgumentCaptor<Supplier<RealtimeWorkspaceRefreshResult>> refreshAction =
        ArgumentCaptor.forClass(Supplier.class);
    verify(realtimeWorkspaceService)
        .requestRefreshWithResult(eq(CodeReviewIllegalRecordService.WORKSPACE_KEY), refreshAction.capture());
    RealtimeWorkspaceRefreshResult result = refreshAction.getValue().get();
    assertThat(result.jobId()).isEqualTo(31L);
    assertThat(result.sourceTables()).containsExactly("merge_requests");
    assertThat(result.plannedTasks()).isEqualTo(1);
    assertThat(result.unsupportedTables()).containsExactly("label_links");
    assertThat(result.factRefreshPlanned()).isTrue();
    assertThat(result.mirrorStatus()).isEqualTo("SUCCESS");
    assertThat(result.factStatus()).isEqualTo("SUCCESS");
    assertThat(result.message()).isEqualTo("fact ok");
  }

  private CodeReviewIllegalRecordSource source(
      Long mergeRequestId,
      Integer mergeRequestIid,
      String repositoryName,
      String mergedBy,
      String owner,
      String moduleName,
      LocalDateTime mergedAt) {
    return source(mergeRequestId, mergeRequestIid, repositoryName, mergedBy, owner, moduleName, mergedAt, "Refactor MR");
  }

  private CodeReviewIllegalRecordSource source(
      Long mergeRequestId,
      Integer mergeRequestIid,
      String repositoryName,
      String mergedBy,
      String owner,
      String moduleName,
      LocalDateTime mergedAt,
      String title) {
    return new CodeReviewIllegalRecordSource(
        mergeRequestId,
        mergeRequestIid,
        2001L,
        title,
        "Project X",
        repositoryName,
        mergedAt,
        mergedBy,
        owner,
        "master",
        moduleName,
        List.of(),
        "DONE",
        15,
        "SCANNED",
        0,
        0.8,
        1,
        120);
  }
}
