package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRuntimeConfigGuardTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRuntimeConfigGuard guard;

  @BeforeEach
  void setUp() {
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    guard = new SyncRuntimeConfigGuard(jdbcTemplate);
  }

  @Test
  void shouldRejectRuntimeSettingChangeWhileMirrorRunIsActive() {
    GitlabSyncConfig current = config();
    GitlabSyncConfig replacement = config();
    replacement.setSyncThreadValue(BigDecimal.valueOf(6));
    when(jdbcTemplate.queryForObject(
            anyString(), eq(Boolean.class), eq(7L), eq("default")))
        .thenReturn(true);

    assertThatThrownBy(() -> guard.verifyChangeAllowed(current, replacement))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("活动同步任务");
  }

  @Test
  void shouldAllowNonRuntimeSettingChangeWithoutQueryingRunState() {
    GitlabSyncConfig current = config();
    GitlabSyncConfig replacement = config();
    replacement.setName("新的显示名称");

    guard.verifyChangeAllowed(current, replacement);

    verify(jdbcTemplate, never())
        .queryForObject(anyString(), eq(Boolean.class), eq(7L), eq("default"));
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(7L);
    config.setName("GitLab");
    config.setSourceInstance("default");
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("secret");
    config.setDockerContainerName("gitlab");
    config.setSyncThreadMode(SyncThreadBudgetResolver.MODE_FIXED);
    config.setSyncThreadValue(BigDecimal.valueOf(2));
    config.setMaxSyncThreads(8);
    return config;
  }
}
