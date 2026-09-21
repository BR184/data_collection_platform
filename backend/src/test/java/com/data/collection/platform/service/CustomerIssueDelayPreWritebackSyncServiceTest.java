package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.CustomerIssueDelayPreWritebackSyncService.Outcome;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerIssueDelayPreWritebackSyncServiceTest {
  private static final long MIRROR_RUN_ID = 77L;

  private SyncRunSubmissionService submissionService;
  private GitlabMirrorProperties properties;
  private CustomerIssueDelayPreWritebackSyncService service;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    submissionService = mock(SyncRunSubmissionService.class);
    properties = new GitlabMirrorProperties();
    service = new CustomerIssueDelayPreWritebackSyncService(submissionService, properties);
    config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("default");
    when(submissionService.submitTableRefresh(any(), any(), any()))
        .thenReturn(
            new SyncRunSubmissionResult(
                MIRROR_RUN_ID,
                SyncType.INCREMENTAL,
                SyncStatus.QUEUED,
                SyncSubmissionAction.QUEUED,
                LocalDateTime.now(),
                "同步已提交，等待调度器执行。"));
  }

  @Test
  void test_configuredTablesSubmitted_returnsRunIdWithoutWaitingForCompletion() {
    Outcome outcome = service.submitPreWritebackSync(config);

    assertThat(outcome).isEqualTo(new Outcome.Submitted(MIRROR_RUN_ID));
    verify(submissionService)
        .submitTableRefresh(
            eq(config),
            eq(List.of("issues", "notes", "label_links", "labels")),
            eq("客户问题延期标签写回前增量刷新"));
  }

  @Test
  void test_syncTablesWithSpacesAndDuplicates_areNormalizedAndDeduplicated() {
    properties.setCustomerIssueDelayPreWritebackSyncTables(" Issues , notes,ISSUES , ");

    service.submitPreWritebackSync(config);

    verify(submissionService)
        .submitTableRefresh(eq(config), eq(List.of("issues", "notes")), any());
  }

  @Test
  void test_noConfiguredSyncTables_returnsRejectedWithoutSubmittingSync() {
    properties.setCustomerIssueDelayPreWritebackSyncTables(" , ");

    Outcome outcome = service.submitPreWritebackSync(config);

    assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
    verify(submissionService, never()).submitTableRefresh(any(), any(), any());
  }

  @Test
  void test_preWritebackSyncDisabled_returnsNotRequiredWithoutSubmittingSync() {
    properties.setCustomerIssueDelayPreWritebackSyncEnabled(false);

    Outcome outcome = service.submitPreWritebackSync(config);

    assertThat(outcome).isInstanceOf(Outcome.NotRequired.class);
    verify(submissionService, never()).submitTableRefresh(any(), any(), any());
  }
}
