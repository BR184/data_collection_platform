package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RealtimeWorkspaceServiceTest {
  private GitlabConfigService configService;
  private RealtimeWorkspaceService workspaceService;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setLastIncrementalSyncAt(LocalDateTime.of(2026, 5, 18, 10, 0));
    when(configService.getConfig()).thenReturn(config);
    workspaceService = new RealtimeWorkspaceService(configService, null);
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
}
