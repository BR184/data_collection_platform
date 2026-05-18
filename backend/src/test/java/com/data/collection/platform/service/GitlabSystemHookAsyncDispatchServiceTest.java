package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSystemHookAsyncDispatchServiceTest {
  private GitlabConfigService configService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabSystemHookPreciseSyncPlanner planner;
  private SyncRunSubmissionService submissionService;
  private GitlabSystemHookAsyncDispatchService dispatchService;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    planner = mock(GitlabSystemHookPreciseSyncPlanner.class);
    submissionService = mock(SyncRunSubmissionService.class);
    dispatchService =
        new GitlabSystemHookAsyncDispatchService(configService, mirrorSchemaService, planner, submissionService);
  }

  @Test
  void shouldSubmitSystemHookRunForPlannedTargetTables() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    Map<String, Object> payload = Map.of("object_kind", "issue");
    when(planner.plan(payload))
        .thenReturn(
            new GitlabSystemHookPreciseSyncPlan(
                "issue:101",
                "101",
                List.of(
                    new GitlabSystemHookPreciseSyncTarget("issues", "id", 101L),
                    new GitlabSystemHookPreciseSyncTarget("issue_assignees", "issue_id", 101L))));

    dispatchService.accept(config, "Issue Hook", payload);

    verify(submissionService)
        .submitRun(
            eq(config),
            eq(SyncType.SYSTEM_HOOK),
            eq(SyncRunType.SYSTEM_HOOK),
            eq(SyncTriggerType.SYSTEM_HOOK),
            eq("System Hook Issue Hook issue:101"),
            eq(List.of("issues", "issue_assignees")),
            eq("issues"),
            argThat(payloadExtras ->
                "issue:101".equals(payloadExtras.get("objectKey"))
                    && payloadExtras.get("preciseTargets") instanceof List<?> targets
                    && targets.size() == 2));
  }
}
