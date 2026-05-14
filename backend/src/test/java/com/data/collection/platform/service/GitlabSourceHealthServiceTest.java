package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GitlabSourceHealthServiceTest {

  @Test
  void shouldReportMergeRequestFactLaggingIndependentlyFromOtherFactTables() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncTaskService taskService = mock(GitlabSyncTaskService.class);
    GitlabSyncLogService logService = mock(GitlabSyncLogService.class);
    GitlabTableSyncPlanningService tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceHealthService service =
        new GitlabSourceHealthService(configService, taskService, logService, tableSyncPlanningService, jdbcTemplate);

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(7L);
    config.setName("CC");
    config.setEnabled(true);
    config.setSourceInstance("cc");
    when(configService.listConfigs()).thenReturn(List.of(config));

    GitlabSyncLog latestLog = new GitlabSyncLog();
    latestLog.setStatus(SyncStatus.SUCCESS);
    latestLog.setFinishedAt(LocalDateTime.of(2026, 5, 7, 10, 0));
    when(logService.findLatest(7L)).thenReturn(latestLog);
    when(jdbcTemplate.queryForList(any(String.class), eq(String.class), eq(7L)))
        .thenReturn(List.of("ods_gitlab_cc_merge_requests"));
    when(jdbcTemplate.queryForObject(eq("select to_regclass(?) is not null"), eq(Boolean.class), any()))
        .thenReturn(true);
    when(jdbcTemplate.queryForObject(
            argThat(sql -> sql != null && sql.contains("\"merge_request_fact\"") && sql.contains("max(updated_at)")),
            eq(LocalDateTime.class),
            eq("cc")))
        .thenReturn(LocalDateTime.of(2026, 5, 7, 9, 55));
    when(jdbcTemplate.queryForObject(
            argThat(sql -> sql != null && sql.contains("\"issue_fact\"") && sql.contains("max(updated_at)")),
            eq(LocalDateTime.class),
            eq("cc")))
        .thenReturn(LocalDateTime.of(2026, 5, 7, 10, 2));
    when(jdbcTemplate.queryForObject(
            argThat(
                sql -> sql != null
                    && sql.contains("\"integration_test_fact\"")
                    && sql.contains("max(updated_at)")),
            eq(LocalDateTime.class),
            eq("cc")))
        .thenReturn(LocalDateTime.of(2026, 5, 7, 10, 3));
    when(jdbcTemplate.queryForObject(
            argThat(sql -> sql != null && sql.contains("count(*)")),
            eq(Long.class),
            eq("cc")))
        .thenReturn(1L);

    GitlabSourceHealthResponse response = service.listHealth().getFirst();

    assertThat(response.factLayerLagging()).isTrue();
    assertThat(response.mergeRequestFactLagging()).isTrue();
    assertThat(response.issueFactLagging()).isFalse();
    assertThat(response.integrationTestFactLagging()).isFalse();
  }

  @Test
  void shouldNotMarkSourceHealthLaggingWhenOnlyNonCodeReviewFactsAreMissing() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncTaskService taskService = mock(GitlabSyncTaskService.class);
    GitlabSyncLogService logService = mock(GitlabSyncLogService.class);
    GitlabTableSyncPlanningService tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceHealthService service =
        new GitlabSourceHealthService(configService, taskService, logService, tableSyncPlanningService, jdbcTemplate);

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(8L);
    config.setName("DGM");
    config.setEnabled(true);
    config.setSourceInstance("dgm");
    when(configService.listConfigs()).thenReturn(List.of(config));

    GitlabSyncLog latestLog = new GitlabSyncLog();
    latestLog.setStatus(SyncStatus.SUCCESS);
    latestLog.setFinishedAt(LocalDateTime.of(2026, 5, 7, 10, 0));
    when(logService.findLatest(8L)).thenReturn(latestLog);
    when(jdbcTemplate.queryForList(any(String.class), eq(String.class), eq(8L)))
        .thenReturn(List.of("ods_gitlab_dgm_merge_requests"));
    when(jdbcTemplate.queryForObject(eq("select to_regclass(?) is not null"), eq(Boolean.class), any()))
        .thenReturn(true);
    when(jdbcTemplate.queryForObject(
            argThat(sql -> sql != null && sql.contains("\"merge_request_fact\"") && sql.contains("max(updated_at)")),
            eq(LocalDateTime.class),
            eq("dgm")))
        .thenReturn(LocalDateTime.of(2026, 5, 7, 10, 1));
    when(jdbcTemplate.queryForObject(
            argThat(sql -> sql != null && sql.contains("count(*)")),
            eq(Long.class),
            eq("dgm")))
        .thenReturn(1L);

    GitlabSourceHealthResponse response = service.listHealth().getFirst();

    assertThat(response.factLayerLagging()).isFalse();
    assertThat(response.mergeRequestFactLagging()).isFalse();
    assertThat(response.issueFactLagging()).isFalse();
    assertThat(response.integrationTestFactLagging()).isFalse();
  }

  @Test
  void shouldMarkDirectSourceBlockedWhenDatabasePasswordIsMissing() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncTaskService taskService = mock(GitlabSyncTaskService.class);
    GitlabSyncLogService logService = mock(GitlabSyncLogService.class);
    GitlabTableSyncPlanningService tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceHealthService service =
        new GitlabSourceHealthService(configService, taskService, logService, tableSyncPlanningService, jdbcTemplate);

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(9L);
    config.setName("Blocked direct source");
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("");
    when(configService.listConfigs()).thenReturn(List.of(config));

    GitlabSourceHealthResponse response = service.listHealth().getFirst();

    assertThat(response.healthStatus()).isEqualTo("BLOCKED");
    assertThat(response.healthMessage()).contains("configuration is incomplete");
    assertThat(response.currentStatus()).isEqualTo(SyncStatus.IDLE);
    assertThat(response.missingRequiredMirrorTables()).isEmpty();
  }

  @Test
  void shouldReportMissingMirrorTableAsDegradedWithSpecificTableName() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncTaskService taskService = mock(GitlabSyncTaskService.class);
    GitlabSyncLogService logService = mock(GitlabSyncLogService.class);
    GitlabTableSyncPlanningService tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceHealthService service =
        new GitlabSourceHealthService(configService, taskService, logService, tableSyncPlanningService, jdbcTemplate);

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(10L);
    config.setName("CC");
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setDockerContainerName("gitlab-web-1");
    config.setSourceInstance("cc");
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(jdbcTemplate.queryForList(any(String.class), eq(String.class), eq(10L)))
        .thenReturn(List.of("ods_gitlab_cc_merge_requests"));
    when(jdbcTemplate.queryForObject(eq("select to_regclass(?) is not null"), eq(Boolean.class), any()))
        .thenReturn(false);
    when(jdbcTemplate.queryForObject(
            argThat(sql -> sql != null && sql.contains("\"merge_request_fact\"") && sql.contains("max(updated_at)")),
            eq(LocalDateTime.class),
            eq("cc")))
        .thenReturn(LocalDateTime.of(2026, 5, 7, 10, 1));
    when(jdbcTemplate.queryForObject(argThat(sql -> sql != null && sql.contains("count(*)")), eq(Long.class), eq("cc")))
        .thenReturn(1L);

    GitlabSourceHealthResponse response = service.listHealth().getFirst();

    assertThat(response.healthStatus()).isEqualTo("DEGRADED");
    assertThat(response.healthMessage()).contains("ods_gitlab_cc_merge_request_metrics");
    assertThat(response.missingRequiredMirrorTables()).contains("ods_gitlab_cc_merge_request_metrics");
  }

  @Test
  void shouldKeepSourceStatusesIsolatedWhenOneSourceIsBlocked() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncTaskService taskService = mock(GitlabSyncTaskService.class);
    GitlabSyncLogService logService = mock(GitlabSyncLogService.class);
    GitlabTableSyncPlanningService tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceHealthService service =
        new GitlabSourceHealthService(configService, taskService, logService, tableSyncPlanningService, jdbcTemplate);

    GitlabSyncConfig blocked = new GitlabSyncConfig();
    blocked.setId(11L);
    blocked.setName("Blocked");
    blocked.setEnabled(true);
    blocked.setSourceEnabled(true);
    blocked.setSourceMode(SourceMode.DIRECT);
    blocked.setDbHost("10.0.0.8");
    blocked.setDbPort(5432);
    blocked.setDbName("gitlabhq_production");
    blocked.setDbUsername("gitlab");

    GitlabSyncConfig healthy = new GitlabSyncConfig();
    healthy.setId(12L);
    healthy.setName("Healthy");
    healthy.setEnabled(true);
    healthy.setSourceEnabled(true);
    healthy.setSourceMode(SourceMode.DOCKER);
    healthy.setDockerContainerName("gitlab-web-1");
    healthy.setSourceInstance("dgm");
    when(configService.listConfigs()).thenReturn(List.of(blocked, healthy));

    GitlabSyncTask blockedTask = new GitlabSyncTask();
    blockedTask.setStatus(SyncStatus.RUNNING);
    when(taskService.findDisplayTask(11L)).thenReturn(blockedTask);
    when(jdbcTemplate.queryForList(any(String.class), eq(String.class), eq(12L)))
        .thenReturn(List.of("ods_gitlab_dgm_merge_requests"));
    when(jdbcTemplate.queryForObject(eq("select to_regclass(?) is not null"), eq(Boolean.class), any()))
        .thenReturn(true);
    when(jdbcTemplate.queryForObject(
            argThat(sql -> sql != null && sql.contains("\"merge_request_fact\"") && sql.contains("max(updated_at)")),
            eq(LocalDateTime.class),
            eq("dgm")))
        .thenReturn(LocalDateTime.of(2026, 5, 7, 10, 1));
    when(jdbcTemplate.queryForObject(argThat(sql -> sql != null && sql.contains("count(*)")), eq(Long.class), eq("dgm")))
        .thenReturn(1L);

    List<GitlabSourceHealthResponse> responses = service.listHealth();

    assertThat(responses).hasSize(2);
    assertThat(responses.get(0).healthStatus()).isEqualTo("BLOCKED");
    assertThat(responses.get(0).currentStatus()).isEqualTo(SyncStatus.RUNNING);
    assertThat(responses.get(1).healthStatus()).isEqualTo("OK");
    assertThat(responses.get(1).currentStatus()).isEqualTo(SyncStatus.IDLE);
  }
}
