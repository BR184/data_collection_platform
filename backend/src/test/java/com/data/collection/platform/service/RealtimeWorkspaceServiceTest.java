package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RealtimeWorkspaceServiceTest {
  private RealtimeWorkspaceSyncMetadataService syncMetadataService;
  private RealtimeWorkspaceService workspaceService;

  @BeforeEach
  void setUp() {
    syncMetadataService = mock(RealtimeWorkspaceSyncMetadataService.class);
    when(syncMetadataService.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(new RealtimeWorkspaceSyncMetadata(
            LocalDateTime.of(2026, 5, 18, 10, 0),
            null,
            null));
    workspaceService = new RealtimeWorkspaceService(syncMetadataService, null);
  }

  @Test
  void shouldSuppressRepeatedRefreshWithinCooldownWindow() {
    AtomicInteger refreshCount = new AtomicInteger();

    var first =
        workspaceService.requestRefreshWithResult(
            "system-test-defect-summary",
            () -> {
              refreshCount.incrementAndGet();
              return new RealtimeWorkspaceRefreshResult(
                  10L,
                  List.of("issues"),
                  1,
                  List.of(),
                  true,
                  "QUEUED",
                  "SUCCESS",
                  "Refresh submitted");
            });
    var second =
        workspaceService.requestRefreshWithResult(
            "system-test-defect-summary",
            () -> {
              refreshCount.incrementAndGet();
              return new RealtimeWorkspaceRefreshResult(
                  11L,
                  List.of("notes"),
                  1,
                  List.of(),
                  true,
                  "QUEUED",
                  "SUCCESS",
                  "Refresh submitted again");
            });

    assertThat(refreshCount).hasValue(1);
    assertThat(first.status()).isEqualTo("READY");
    assertThat(second.status()).isEqualTo("READY");
    assertThat(workspaceService.getStatus("other-board").message()).isEqualTo("已展示当前可用数据");
    assertThat(second.jobId()).isEqualTo(10L);
    assertThat(second.sourceTables()).containsExactly("issues");
  }

  @Test
  void shouldTrackRefreshInProgressForConcurrentCall() {
    AtomicInteger refreshCount = new AtomicInteger();

    var response =
        workspaceService.requestRefreshWithResult(
            "customer-issue-defect-summary",
            () -> {
              refreshCount.incrementAndGet();
              var concurrent =
                  workspaceService.requestRefreshWithResult(
                      "customer-issue-defect-summary",
                      () -> {
                        refreshCount.incrementAndGet();
                        return null;
                      });
              assertThat(concurrent.refreshing()).isTrue();
              assertThat(concurrent.message()).isEqualTo("已开始刷新最新数据");
              return new RealtimeWorkspaceRefreshResult(
                  12L,
                  List.of("issues"),
                  1,
                  List.of(),
                  true,
                  "QUEUED",
                  "SUCCESS",
                  "Refresh submitted");
            });

    assertThat(refreshCount).hasValue(1);
    assertThat(response.status()).isEqualTo("READY");
  }

  @Test
  void shouldKeepWorkspaceRefreshingUntilItsPersistedFactChildSucceeds() {
    RealtimeWorkspaceRefreshProgressService progressService =
        mock(RealtimeWorkspaceRefreshProgressService.class);
    RealtimeWorkspaceService service =
        new RealtimeWorkspaceService(syncMetadataService, progressService, null);
    LocalDateTime startedAt = LocalDateTime.of(2026, 5, 18, 10, 1);
    when(progressService.findByMirrorRunId(21L, "customer-issue-cc-product-records"))
        .thenReturn(
            new RealtimeWorkspaceRefreshProgress(
                21L,
                "SUCCESS",
                "QUEUED",
                true,
                startedAt,
                null));

    var submitted =
        service.requestRefreshWithResult(
            "customer-issue-cc-product-records",
            () ->
                new RealtimeWorkspaceRefreshResult(
                    21L,
                    List.of("issues"),
                    1,
                    List.of(),
                    true,
                    "QUEUED",
                    "QUEUED",
                    "已提交"));

    assertThat(submitted.refreshing()).isTrue();
    assertThat(submitted.status()).isEqualTo("REFRESHING");
    assertThat(submitted.mirrorStatus()).isEqualTo("SUCCESS");
    assertThat(submitted.factStatus()).isEqualTo("QUEUED");

    LocalDateTime finishedAt = LocalDateTime.of(2026, 5, 18, 10, 2);
    when(progressService.findByMirrorRunId(21L, "customer-issue-cc-product-records"))
        .thenReturn(
            new RealtimeWorkspaceRefreshProgress(
                21L,
                "SUCCESS",
                "SUCCESS",
                true,
                startedAt,
                finishedAt));

    var completed = service.getStatus("customer-issue-cc-product-records");

    assertThat(completed.refreshing()).isFalse();
    assertThat(completed.status()).isEqualTo("READY");
    assertThat(completed.message()).isEqualTo("已展示最新事实数据");
    assertThat(completed.lastRefreshFinishedAt()).isEqualTo(finishedAt);
  }

  @Test
  void shouldRestoreLatestPersistedWorkspaceProgressWithoutInMemoryState() {
    RealtimeWorkspaceRefreshProgressService progressService =
        mock(RealtimeWorkspaceRefreshProgressService.class);
    RealtimeWorkspaceService service =
        new RealtimeWorkspaceService(syncMetadataService, progressService, null);
    LocalDateTime startedAt = LocalDateTime.of(2026, 5, 18, 10, 1);
    when(progressService.findLatestForWorkspace("customer-issue-cc-product-records"))
        .thenReturn(
            new RealtimeWorkspaceRefreshProgress(
                31L,
                "SUCCESS",
                "RUNNING",
                true,
                startedAt,
                null));

    var status = service.getStatus("customer-issue-cc-product-records");

    assertThat(status.refreshing()).isTrue();
    assertThat(status.status()).isEqualTo("REFRESHING");
    assertThat(status.jobId()).isEqualTo(31L);
    assertThat(status.factStatus()).isEqualTo("RUNNING");
  }

}
