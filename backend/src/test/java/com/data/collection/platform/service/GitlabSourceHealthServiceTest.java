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
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceHealthService service =
        new GitlabSourceHealthService(configService, taskService, logService, jdbcTemplate);

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
}
