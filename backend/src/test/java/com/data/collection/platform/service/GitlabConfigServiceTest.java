package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabSyncConfigMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabConfigServiceTest {
  private GitlabSyncConfigMapper configMapper;
  private GitlabConfigService configService;

  @BeforeEach
  void setUp() {
    configMapper = mock(GitlabSyncConfigMapper.class);
    configService = new GitlabConfigService(configMapper);
  }

  @Test
  void shouldRejectCompensationIntervalBelowMinimum() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setCompensationIntervalMinutes(0);

    assertThatThrownBy(() -> configService.saveConfig(config)).isInstanceOf(BizException.class);

    verify(configMapper, never()).insert(any(GitlabSyncConfig.class));
  }

  @Test
  void shouldRejectCompensationIntervalAboveMaximum() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setCompensationIntervalMinutes(721);

    assertThatThrownBy(() -> configService.saveConfig(config)).isInstanceOf(BizException.class);

    verify(configMapper, never()).insert(any(GitlabSyncConfig.class));
  }

  @Test
  void shouldPreserveStoredSecretsWhenSaveRequestLeavesSecretsBlank() {
    GitlabSyncConfig current = persistedConfig();
    when(configMapper.selectOne(any())).thenReturn(current);
    when(configMapper.selectById(1L)).thenReturn(current);

    GitlabSyncConfig input = baseInput();
    input.setDbPassword("");
    input.setWebhookSecret(" ");

    configService.saveConfig(input);

    verify(configMapper).updateById(argThat((GitlabSyncConfig config) ->
        "stored-database-secret".equals(config.getDbPassword())
            && "stored-webhook-secret".equals(config.getWebhookSecret())));
  }

  @Test
  void shouldAllowDisablingAutoSyncEvenWhenLegacyEnabledWasTrue() {
    GitlabSyncConfig current = persistedConfig();
    when(configMapper.selectOne(any())).thenReturn(current);
    when(configMapper.selectById(1L)).thenReturn(current);

    GitlabSyncConfig input = baseInput();
    input.setEnabled(true);
    input.setAutoSyncEnabled(false);

    configService.saveConfig(input);

    verify(configMapper).updateById(argThat((GitlabSyncConfig config) -> !config.isEnabled() && !config.isAutoSyncEnabled()));
  }

  @Test
  void shouldNormalizeBlankSourceInstanceToDefault() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig input = baseInput();
    input.setSourceInstance(" ");
    configService.saveConfig(input);

    verify(configMapper).insert(argThat((GitlabSyncConfig config) -> "default".equals(config.getSourceInstance())));
  }

  @Test
  void shouldRejectChangingConfigToExistingSourceInstance() {
    GitlabSyncConfig current = persistedConfig();
    current.setId(1L);
    current.setSourceInstance("cc");
    GitlabSyncConfig existing = persistedConfig();
    existing.setId(2L);
    existing.setSourceInstance("dgm");
    when(configMapper.selectById(1L)).thenReturn(current);
    when(configMapper.selectOne(any())).thenReturn(existing);

    GitlabSyncConfig input = baseInput();
    input.setId(1L);
    input.setSourceInstance("DGM");

    assertThatThrownBy(() -> configService.saveConfig(input))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("dgm");
    verify(configMapper, never()).updateById(any(GitlabSyncConfig.class));
  }

  @Test
  void shouldRejectTooLongSourceInstanceBeforeSaving() {
    GitlabSyncConfig input = baseInput();
    input.setSourceInstance("a".repeat(GitlabSourceInstanceSupport.MAX_SOURCE_INSTANCE_LENGTH + 1));

    assertThatThrownBy(() -> configService.saveConfig(input)).isInstanceOf(BizException.class);
    verify(configMapper, never()).insert(any(GitlabSyncConfig.class));
    verify(configMapper, never()).updateById(any(GitlabSyncConfig.class));
  }

  private GitlabSyncConfig persistedConfig() {
    GitlabSyncConfig config = baseInput();
    config.setId(1L);
    config.setEnabled(true);
    config.setAutoSyncEnabled(true);
    config.setDbPassword("stored-database-secret");
    config.setWebhookSecret("stored-webhook-secret");
    config.setCreatedAt(LocalDateTime.now().minusDays(1));
    return config;
  }

  private GitlabSyncConfig baseInput() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setName("GitLab default source");
    config.setEnabled(false);
    config.setSourceInstance("cc");
    config.setAutoSyncEnabled(false);
    config.setSourceMode(SourceMode.DIRECT);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setWhitelistTables(List.of());
    config.setDbHost("localhost");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("new-database-secret");
    config.setDockerContainerName("gitlab-data-web-1");
    config.setWebhookSecret("new-webhook-secret");
    config.setWebhookProjectId(1L);
    config.setCompensationIntervalMinutes(10);
    return config;
  }
}
