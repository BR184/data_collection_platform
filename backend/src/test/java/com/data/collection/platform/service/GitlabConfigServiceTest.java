package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabSyncConfigMapper;
import com.data.collection.platform.service.sync.SyncThreadBudgetResolver;
import java.math.BigDecimal;
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
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(16);
    configService = new GitlabConfigService(configMapper, properties);
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
    input.setSystemHookSecret(" ");

    configService.saveConfig(input);

    verify(configMapper).updateById(argThat((GitlabSyncConfig config) ->
        "stored-database-secret".equals(config.getDbPassword())
            && "stored-systemHook-secret".equals(config.getSystemHookSecret())));
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

    verify(configMapper).updateById(argThat((GitlabSyncConfig config) ->
        config.isEnabled()
            && Boolean.TRUE.equals(config.getSourceEnabled())
            && !config.isAutoSyncEnabled()));
  }

  @Test
  void shouldRejectEnabledSystemHookWithoutSecret() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig input = baseInput();
    input.setSystemHookEnabled(true);
    input.setSystemHookSecret("");

    assertThatThrownBy(() -> configService.saveConfig(input))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("System Hook");
    verify(configMapper, never()).insert(any(GitlabSyncConfig.class));
  }

  @Test
  void shouldRejectDuplicateEnabledSystemHookSecret() {
    when(configMapper.selectOne(any())).thenReturn(null);
    GitlabSyncConfig existing = persistedConfig();
    existing.setId(99L);
    existing.setSourceEnabled(true);
    existing.setSystemHookEnabled(true);
    existing.setSystemHookSecret("shared-secret");
    when(configMapper.selectList(any())).thenReturn(List.of(existing));

    GitlabSyncConfig input = baseInput();
    input.setSystemHookEnabled(true);
    input.setSystemHookSecret("shared-secret");

    assertThatThrownBy(() -> configService.saveConfig(input))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("System Hook Secret");
    verify(configMapper, never()).insert(any(GitlabSyncConfig.class));
  }

  @Test
  void shouldResolveSystemHookConfigOnlyWhenSystemHookEnabledAndSecretMatches() {
    GitlabSyncConfig disabledSystemHook = persistedConfig();
    disabledSystemHook.setId(1L);
    disabledSystemHook.setSourceEnabled(true);
    disabledSystemHook.setSystemHookEnabled(false);
    disabledSystemHook.setSystemHookSecret("disabled-secret");
    GitlabSyncConfig enabledSystemHook = persistedConfig();
    enabledSystemHook.setId(2L);
    enabledSystemHook.setSourceEnabled(true);
    enabledSystemHook.setSystemHookEnabled(true);
    enabledSystemHook.setSystemHookSecret("enabled-secret");
    when(configMapper.selectList(any())).thenReturn(List.of(disabledSystemHook, enabledSystemHook));

    org.assertj.core.api.Assertions.assertThat(configService.getConfigForSystemHook("enabled-secret"))
        .isSameAs(enabledSystemHook);
    assertThatThrownBy(() -> configService.getConfigForSystemHook("disabled-secret"))
        .isInstanceOf(BizException.class);
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
  void shouldNormalizeMissingThreadBudgetToDefaults() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig input = baseInput();
    input.setSyncThreadMode(null);
    input.setSyncThreadValue(null);
    input.setMaxSyncThreads(null);

    configService.saveConfig(input);

    verify(configMapper).insert(argThat((GitlabSyncConfig config) ->
        SyncThreadBudgetResolver.MODE_FIXED.equals(config.getSyncThreadMode())
            && BigDecimal.valueOf(2).compareTo(config.getSyncThreadValue()) == 0
            && Integer.valueOf(16).equals(config.getMaxSyncThreads())));
  }

  @Test
  void shouldPersistAllWhitelistModeWithoutNormalization() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig input = baseInput();
    input.setWhitelistMode(WhitelistMode.ALL);
    input.setWhitelistTables(List.of("issues", "projects"));

    configService.saveConfig(input);

    verify(configMapper).insert(argThat((GitlabSyncConfig config) ->
        config.getWhitelistMode() == WhitelistMode.ALL
            && config.getWhitelistTables().equals(List.of("issues", "projects"))));
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

  @Test
  void shouldRejectMissingExplicitConfigIdInsteadOfReturningDefaultConfig() {
    when(configMapper.selectById(999L)).thenReturn(null);

    assertThatThrownBy(() -> configService.getConfigById(999L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("999");
  }

  @Test
  void shouldReturnDisabledDraftConfigWhenNoConfigExists() {
    when(configMapper.selectList(any())).thenReturn(List.of());

    GitlabSyncConfig config = configService.listConfigs().getFirst();

    assertThat(config.getId()).isNull();
    assertThat(config.isEnabled()).isFalse();
    assertThat(config.getSourceEnabled()).isFalse();
    assertThat(config.isAutoSyncEnabled()).isFalse();
  }

  @Test
  void shouldRejectAutoSyncForIncompleteDirectSource() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig input = baseInput();
    input.setEnabled(true);
    input.setSourceEnabled(true);
    input.setAutoSyncEnabled(true);
    input.setDbPassword("");

    assertThatThrownBy(() -> configService.saveConfig(input))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("连接配置不完整");
    verify(configMapper, never()).insert(any(GitlabSyncConfig.class));
  }

  @Test
  void shouldAllowEnabledIncompleteSourceWhenAutoSyncIsOff() {
    when(configMapper.selectOne(any())).thenReturn(null);

    GitlabSyncConfig input = baseInput();
    input.setEnabled(true);
    input.setSourceEnabled(true);
    input.setAutoSyncEnabled(false);
    input.setDbPassword("");

    configService.saveConfig(input);

    verify(configMapper).insert(argThat((GitlabSyncConfig config) ->
        config.isEnabled()
            && Boolean.TRUE.equals(config.getSourceEnabled())
            && !config.isAutoSyncEnabled()));
  }

  private GitlabSyncConfig persistedConfig() {
    GitlabSyncConfig config = baseInput();
    config.setId(1L);
    config.setEnabled(true);
    config.setAutoSyncEnabled(true);
    config.setDbPassword("stored-database-secret");
    config.setSystemHookSecret("stored-systemHook-secret");
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
    config.setSystemHookSecret("new-systemHook-secret");
    config.setSystemHookProjectId(1L);
    config.setCompensationIntervalMinutes(10);
    config.setSyncThreadMode(SyncThreadBudgetResolver.MODE_FIXED);
    config.setSyncThreadValue(BigDecimal.valueOf(2));
    config.setMaxSyncThreads(16);
    return config;
  }
}
