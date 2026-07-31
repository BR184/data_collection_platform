package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthoritativeRelationReconciliationServiceTest {

  @Test
  void test_system_test_workspace_requires_reconciliation_without_verified_full_label_scan() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(7L);
    when(configService.getConfig()).thenReturn(config);
    when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(7L), eq("default")))
        .thenReturn(false);
    AuthoritativeRelationReconciliationService service =
        new AuthoritativeRelationReconciliationService(jdbcTemplate, configService);

    assertThat(service.requiresLabelLinkReconciliation("system-test-defect-summary")).isTrue();
  }

  @Test
  void test_verified_full_label_scan_allows_current_status() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(7L);
    when(configService.getConfig()).thenReturn(config);
    when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(7L), eq("default")))
        .thenReturn(true);
    AuthoritativeRelationReconciliationService service =
        new AuthoritativeRelationReconciliationService(jdbcTemplate, configService);

    assertThat(service.requiresLabelLinkReconciliation("system-test-defect-summary")).isFalse();
  }

  @Test
  void test_nonFactWorkspace_doesNotQueryReconciliationState() {
    AuthoritativeRelationReconciliationService service =
        new AuthoritativeRelationReconciliationService(mock(JdbcTemplate.class), mock(GitlabConfigService.class));

    assertThat(service.requiresLabelLinkReconciliation("database-browser")).isFalse();
  }
}
