package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RealtimeWorkspaceSyncMetadataServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void shouldUseLatestSuccessfulLocalIssueFactBuildForCustomerIssueWorkspace() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    CodeReviewMatchModeConfigService matchModeConfigService =
        mock(CodeReviewMatchModeConfigService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceInstance("ignored-by-single-source-platform");
    LocalDateTime startedAt = LocalDateTime.of(2026, 7, 22, 13, 40);
    LocalDateTime finishedAt = LocalDateTime.of(2026, 7, 22, 13, 42);
    when(configService.getConfig()).thenReturn(config);
    when(jdbcTemplate.query(
        contains("from fact_build_tasks"),
        any(RowMapper.class),
        eq("default")))
        .thenReturn(List.of(new RealtimeWorkspaceSyncMetadata(finishedAt, startedAt, finishedAt)));
    RealtimeWorkspaceSyncMetadataService service =
        new RealtimeWorkspaceSyncMetadataService(
            configService,
            matchModeConfigService,
            new JsonUtils(new ObjectMapper()),
            jdbcTemplate);

    RealtimeWorkspaceSyncMetadata metadata =
        service.resolve("customer-issue-cc-product-records", Map.of());

    assertThat(metadata.lastSyncedAt()).isEqualTo(finishedAt);
    assertThat(metadata.taskStartedAt()).isEqualTo(startedAt);
    assertThat(metadata.taskFinishedAt()).isEqualTo(finishedAt);
    verifyNoInteractions(matchModeConfigService);
  }
}
