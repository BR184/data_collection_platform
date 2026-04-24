package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CodeReviewIllegalRecordServiceTest {

  @Mock
  private GitlabMirrorSyncService gitlabMirrorSyncService;

  @Mock
  private RealtimeWorkspaceService realtimeWorkspaceService;

  @Mock
  private FactBuildService factBuildService;

  @Mock
  private CodeReviewIllegalRecordSourceLoader sourceLoader;

  private CodeReviewIllegalRecordService service;

  @BeforeEach
  void setUp() {
    service = new CodeReviewIllegalRecordService(
        gitlabMirrorSyncService,
        realtimeWorkspaceService,
        factBuildService,
        sourceLoader,
        new ObjectMapper(),
        "http://gitlab.example.com");
  }

  @Test
  void shouldKeepListRecordsBehaviorAfterRefactor() {
    when(sourceLoader.loadSources(anyMap())).thenReturn(List.of(
        source(101L, 12, "repo-b", "Alice", "", LocalDateTime.of(2026, 4, 8, 10, 0)),
        source(102L, 5, "repo-a", "Bob", "", LocalDateTime.of(2026, 4, 9, 10, 0))));

    CodeReviewIllegalRecordListResponse response = service.listRecords(
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
        1,
        20,
        "mergeRequestIid",
        "asc",
        null);

    assertThat(response.total()).isEqualTo(2);
    assertThat(response.sortField()).isEqualTo("mergeRequestIid");
    assertThat(response.sortOrder()).isEqualTo("asc");
    assertThat(response.records()).extracting(item -> item.mergeRequestIid()).containsExactly(5, 12);
    assertThat(response.records()).allMatch(item -> item.mergeRequestLink() != null);
  }

  @Test
  void shouldBuildFilterOptionsFromLoadedSources() {
    when(sourceLoader.loadSources(anyMap())).thenReturn(List.of(
        source(101L, 12, "repo-b", "Alice", "module-b", LocalDateTime.of(2026, 4, 8, 10, 0)),
        source(102L, 5, "repo-a", "Bob", "module-a", LocalDateTime.of(2026, 4, 9, 10, 0))));

    CodeReviewIllegalRecordFilterOptionsResponse response = service.getFilterOptions(null);

    assertThat(response.requestTypes()).hasSize(1);
    assertThat(response.repositoryNames()).extracting(item -> item.value()).containsExactly("repo-a", "repo-b");
    assertThat(response.mergedBys()).extracting(item -> item.value()).containsExactly("Alice", "Bob");
    assertThat(response.moduleNames()).extracting(item -> item.value()).containsExactly("module-a", "module-b");
  }

  private CodeReviewIllegalRecordSource source(
      Long mergeRequestId,
      Integer mergeRequestIid,
      String repositoryName,
      String mergedBy,
      String moduleName,
      LocalDateTime mergedAt) {
    return new CodeReviewIllegalRecordSource(
        mergeRequestId,
        mergeRequestIid,
        2001L,
        "Refactor MR",
        "Project X",
        repositoryName,
        mergedAt,
        mergedBy,
        "Owner A",
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
