package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabSyncConfigMapper;
import com.data.collection.platform.service.sync.SyncThreadBudgetResolver;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class GitlabConfigService {
  static final int MIN_COMPENSATION_INTERVAL_MINUTES = 1;
  static final int MAX_COMPENSATION_INTERVAL_MINUTES = 720;
  static final int DEFAULT_COMPENSATION_INTERVAL_MINUTES = 360;

  private final GitlabSyncConfigMapper configMapper;
  private final GitlabMirrorProperties properties;

  public GitlabConfigService(GitlabSyncConfigMapper configMapper, GitlabMirrorProperties properties) {
    this.configMapper = configMapper;
    this.properties = properties;
  }

  public GitlabSyncConfig getConfig() {
    GitlabSyncConfig config =
        configMapper.selectOne(new LambdaQueryWrapper<GitlabSyncConfig>()
            .orderByAsc(GitlabSyncConfig::getId)
            .last("limit 1"));
    if (config == null) {
      return defaultConfig();
    }
    normalizePersistedSourceInstance(config);
    return config;
  }

  public List<GitlabSyncConfig> listConfigs() {
    List<GitlabSyncConfig> configs =
        configMapper.selectList(new LambdaQueryWrapper<GitlabSyncConfig>()
            .orderByAsc(GitlabSyncConfig::getSourceInstance)
            .orderByAsc(GitlabSyncConfig::getId));
    if (configs == null || configs.isEmpty()) {
      return List.of(defaultConfig());
    }
    configs.forEach(this::normalizePersistedSourceInstance);
    return configs;
  }

  public GitlabSyncConfig getConfigById(Long id) {
    if (id == null) {
      return getConfig();
    }
    GitlabSyncConfig config = configMapper.selectById(id);
    if (config == null) {
      throw new BizException("GitLab 数据源配置不存在：" + id);
    }
    normalizePersistedSourceInstance(config);
    return config;
  }

  public GitlabSyncConfig getConfigForSystemHook(String secret) {
    List<GitlabSyncConfig> configs =
        configMapper.selectList(new LambdaQueryWrapper<GitlabSyncConfig>()
            .eq(GitlabSyncConfig::getSourceEnabled, true)
            .eq(GitlabSyncConfig::getSystemHookEnabled, true)
            .orderByAsc(GitlabSyncConfig::getId));
    if (configs == null || configs.isEmpty()) {
      throw new BizException("未启用 GitLab System Hook 数据源");
    }
    configs.forEach(this::normalizePersistedSourceInstance);
    configs = configs.stream()
        .filter(config -> Boolean.TRUE.equals(config.getSourceEnabled()))
        .filter(config -> Boolean.TRUE.equals(config.getSystemHookEnabled()))
        .toList();
    if (configs.isEmpty()) {
      throw new BizException("未启用 GitLab System Hook 数据源");
    }
    if (secret != null && !secret.isBlank()) {
      List<GitlabSyncConfig> matches = configs.stream()
          .filter(config -> config.getSystemHookSecret() != null && !config.getSystemHookSecret().isBlank())
          .filter(config -> config.getSystemHookSecret().equals(secret))
          .toList();
      if (matches.size() == 1) {
        return matches.getFirst();
      }
      if (matches.size() > 1) {
        throw new BizException("存在多个 GitLab 数据源使用相同的 System Hook Secret");
      }
    }
    throw new BizException("System Hook Secret 未提供或未匹配到已启用的数据源");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public GitlabSyncConfig saveConfig(GitlabSyncConfig input) {
    GitlabSyncConfig current = resolveCurrentForSave(input);
    GitlabSyncConfig normalized = normalize(input, current);
    LocalDateTime now = LocalDateTime.now();
    if (current.getId() == null) {
      normalized.setCreatedAt(now);
      normalized.setUpdatedAt(now);
      configMapper.insert(normalized);
    } else {
      normalized.setId(current.getId());
      normalized.setCreatedAt(current.getCreatedAt());
      normalized.setLastFullSyncAt(current.getLastFullSyncAt());
      normalized.setLastIncrementalSyncAt(current.getLastIncrementalSyncAt());
      normalized.setUpdatedAt(now);
      configMapper.updateById(normalized);
    }
    return getConfigById(normalized.getId());
  }

  public boolean isReadyForScheduledSync(GitlabSyncConfig config) {
    return config != null
        && config.getId() != null
        && isSourceEnabled(config)
        && config.isAutoSyncEnabled()
        && isSourceConfigured(config);
  }

  public boolean isSourceConfigured(GitlabSyncConfig config) {
    if (config == null) {
      return false;
    }
    SourceMode sourceMode = config.getSourceMode() == null ? SourceMode.DOCKER : config.getSourceMode();
    if (sourceMode == SourceMode.DIRECT) {
      return StringUtils.hasText(config.getDbHost())
          && config.getDbPort() != null
          && config.getDbPort() > 0
          && StringUtils.hasText(config.getDbName())
          && StringUtils.hasText(config.getDbUsername())
          && StringUtils.hasText(config.getDbPassword());
    }
    return StringUtils.hasText(config.getDockerContainerName())
        && StringUtils.hasText(config.getDbName());
  }

  public String sourceReadinessIssue(GitlabSyncConfig config) {
    if (config == null) {
      return "source config is missing";
    }
    if (config.getId() == null) {
      return "source config is not persisted";
    }
    if (!isSourceEnabled(config)) {
      return "source is disabled";
    }
    if (!config.isAutoSyncEnabled()) {
      return "auto sync is disabled";
    }
    if (!isSourceConfigured(config)) {
      return "source connection settings are incomplete";
    }
    return "";
  }

  public void updateSyncTime(Long id, boolean fullSync) {
    LocalDateTime now = LocalDateTime.now();
    LambdaUpdateWrapper<GitlabSyncConfig> updateWrapper = new LambdaUpdateWrapper<GitlabSyncConfig>()
        .eq(GitlabSyncConfig::getId, id)
        .set(GitlabSyncConfig::getUpdatedAt, now);
    if (fullSync) {
      updateWrapper.set(GitlabSyncConfig::getLastFullSyncAt, now);
    } else {
      updateWrapper.set(GitlabSyncConfig::getLastIncrementalSyncAt, now);
    }
    configMapper.update(null, updateWrapper);
  }

  public void resetSyncTime(Long id) {
    if (id == null) {
      return;
    }
    configMapper.update(
        null,
        new LambdaUpdateWrapper<GitlabSyncConfig>()
            .eq(GitlabSyncConfig::getId, id)
            .set(GitlabSyncConfig::getLastFullSyncAt, null)
            .set(GitlabSyncConfig::getLastIncrementalSyncAt, null)
            .set(GitlabSyncConfig::getUpdatedAt, LocalDateTime.now()));
  }

  private GitlabSyncConfig resolveCurrentForSave(GitlabSyncConfig input) {
    if (input != null && input.getId() != null) {
      GitlabSyncConfig byId = configMapper.selectById(input.getId());
      if (byId != null) {
        normalizePersistedSourceInstance(byId);
        ensureSourceInstanceAvailableForUpdate(input, byId);
        return byId;
      }
    }
    String sourceInstance =
        GitlabSourceInstanceSupport.normalizeSourceInstance(input == null ? null : input.getSourceInstance());
    validateSourceInstance(sourceInstance);
    GitlabSyncConfig bySource =
        configMapper.selectOne(new LambdaQueryWrapper<GitlabSyncConfig>()
            .eq(GitlabSyncConfig::getSourceInstance, sourceInstance)
            .last("limit 1"));
    if (bySource != null) {
      normalizePersistedSourceInstance(bySource);
      return bySource;
    }
    return defaultConfig(sourceInstance);
  }

  private void ensureSourceInstanceAvailableForUpdate(GitlabSyncConfig input, GitlabSyncConfig current) {
    String nextSourceInstance =
        GitlabSourceInstanceSupport.normalizeSourceInstance(input == null ? null : input.getSourceInstance());
    validateSourceInstance(nextSourceInstance);
    if (nextSourceInstance.equals(current.getSourceInstance())) {
      return;
    }
    GitlabSyncConfig existing =
        configMapper.selectOne(new LambdaQueryWrapper<GitlabSyncConfig>()
            .eq(GitlabSyncConfig::getSourceInstance, nextSourceInstance)
            .last("limit 1"));
    if (existing != null && existing.getId() != null && !existing.getId().equals(current.getId())) {
      throw new BizException("GitLab 数据源标识已存在：" + nextSourceInstance);
    }
  }

  private GitlabSyncConfig defaultConfig() {
    return defaultConfig(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
  }

  private GitlabSyncConfig defaultConfig(String sourceInstance) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setName("GitLab default source");
    config.setEnabled(false);
    config.setSourceEnabled(false);
    config.setSourceInstance(GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance));
    config.setAutoSyncEnabled(false);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setWhitelistTables(List.of());
    config.setDbHost("localhost");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("");
    config.setDockerContainerName("gitlab-data-web-1");
    config.setSystemHookEnabled(false);
    config.setCompensationIntervalMinutes(DEFAULT_COMPENSATION_INTERVAL_MINUTES);
    config.setSyncThreadMode(SyncThreadBudgetResolver.MODE_FIXED);
    config.setSyncThreadValue(SyncThreadBudgetResolver.DEFAULT_FIXED_THREAD_VALUE);
    config.setMaxSyncThreads(Math.max(1, properties.getMaxSyncThreads()));
    return config;
  }

  private GitlabSyncConfig normalize(GitlabSyncConfig config, GitlabSyncConfig current) {
    GitlabSyncConfig normalized = new GitlabSyncConfig();
    boolean sourceEnabled = config.getSourceEnabled() == null ? config.isEnabled() : config.getSourceEnabled();
    boolean autoSyncEnabled = config.isAutoSyncEnabled();
    String sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(config.getSourceInstance());
    validateSourceInstance(sourceInstance);
    String resolvedSystemHookSecret = resolveSecret(config.getSystemHookSecret(), current.getSystemHookSecret());
    boolean systemHookEnabled = resolveSystemHookEnabled(config, current, resolvedSystemHookSecret);
    normalized.setId(current.getId());
    normalized.setName(config.getName());
    normalized.setEnabled(sourceEnabled);
    normalized.setSourceEnabled(sourceEnabled);
    normalized.setSourceInstance(sourceInstance);
    normalized.setAutoSyncEnabled(autoSyncEnabled);
    normalized.setSourceMode(config.getSourceMode() == null ? SourceMode.DOCKER : config.getSourceMode());
    normalized.setWhitelistMode(normalizeWhitelistMode(config.getWhitelistMode()));
    normalized.setWhitelistTables(config.getWhitelistTables() == null ? List.of() : config.getWhitelistTables());
    normalized.setDbHost(config.getDbHost());
    normalized.setDbPort(config.getDbPort());
    normalized.setDbName(config.getDbName());
    normalized.setDbUsername(config.getDbUsername());
    normalized.setDbPassword(resolveSecret(config.getDbPassword(), current.getDbPassword()));
    normalized.setDockerContainerName(config.getDockerContainerName());
    normalized.setSystemHookSecret(resolvedSystemHookSecret);
    normalized.setSystemHookEnabled(systemHookEnabled);
    normalized.setSystemHookProjectId(config.getSystemHookProjectId());
    normalized.setCompensationIntervalMinutes(normalizeCompensationInterval(config.getCompensationIntervalMinutes()));
    String syncThreadMode = normalizeSyncThreadMode(config.getSyncThreadMode(), current.getSyncThreadMode());
    normalized.setSyncThreadMode(syncThreadMode);
    normalized.setSyncThreadValue(
        normalizeSyncThreadValue(syncThreadMode, config.getSyncThreadValue(), current.getSyncThreadValue()));
    normalized.setMaxSyncThreads(normalizeMaxSyncThreads(config.getMaxSyncThreads(), current.getMaxSyncThreads()));
    validateSystemHookConfig(normalized);
    validateSourceConfigForAutomaticSync(normalized);
    return normalized;
  }

  private void validateSourceConfigForAutomaticSync(GitlabSyncConfig normalized) {
    if (!isSourceEnabled(normalized) || !normalized.isAutoSyncEnabled() || isSourceConfigured(normalized)) {
      return;
    }
    throw new BizException(
        "GitLab source connection settings are incomplete; disable auto sync or complete the source config");
  }

  private boolean isSourceEnabled(GitlabSyncConfig config) {
    return Boolean.TRUE.equals(config.getSourceEnabled() == null ? config.isEnabled() : config.getSourceEnabled());
  }

  private WhitelistMode normalizeWhitelistMode(WhitelistMode whitelistMode) {
    if (whitelistMode == null) {
      return WhitelistMode.RECOMMENDED;
    }
    return whitelistMode;
  }

  private boolean resolveSystemHookEnabled(
      GitlabSyncConfig config,
      GitlabSyncConfig current,
      String resolvedSystemHookSecret) {
    if (config.getSystemHookEnabled() != null) {
      return config.getSystemHookEnabled();
    }
    if (current.getSystemHookEnabled() != null) {
      return current.getSystemHookEnabled();
    }
    return resolvedSystemHookSecret != null && !resolvedSystemHookSecret.isBlank();
  }

  private void validateSystemHookConfig(GitlabSyncConfig normalized) {
    if (!Boolean.TRUE.equals(normalized.getSystemHookEnabled())) {
      return;
    }
    if (normalized.getSystemHookSecret() == null || normalized.getSystemHookSecret().isBlank()) {
      throw new BizException("启用 System Hook 时必须配置唯一的 Secret");
    }
    List<GitlabSyncConfig> matches =
        configMapper.selectList(new LambdaQueryWrapper<GitlabSyncConfig>()
            .eq(GitlabSyncConfig::getSourceEnabled, true)
            .eq(GitlabSyncConfig::getSystemHookEnabled, true)
            .eq(GitlabSyncConfig::getSystemHookSecret, normalized.getSystemHookSecret()));
    boolean duplicate = matches != null && matches.stream()
        .anyMatch(match -> match.getId() != null && !match.getId().equals(normalized.getId()));
    if (duplicate) {
      throw new BizException("System Hook Secret 已被其他 GitLab 数据源使用");
    }
  }

  private Integer normalizeCompensationInterval(Integer intervalMinutes) {
    int effectiveValue = intervalMinutes == null ? DEFAULT_COMPENSATION_INTERVAL_MINUTES : intervalMinutes;
    if (effectiveValue < MIN_COMPENSATION_INTERVAL_MINUTES || effectiveValue > MAX_COMPENSATION_INTERVAL_MINUTES) {
      throw new BizException(
          "补偿间隔仅支持 "
              + MIN_COMPENSATION_INTERVAL_MINUTES
              + " 到 "
              + MAX_COMPENSATION_INTERVAL_MINUTES
              + " 分钟");
    }
    return effectiveValue;
  }

  private String normalizeSyncThreadMode(String nextMode, String currentMode) {
    String effectiveMode =
        nextMode == null || nextMode.isBlank()
            ? currentMode
            : nextMode.trim().toUpperCase(Locale.ROOT);
    if (effectiveMode == null || effectiveMode.isBlank()) {
      return SyncThreadBudgetResolver.MODE_FIXED;
    }
    effectiveMode = effectiveMode.trim().toUpperCase(Locale.ROOT);
    if (SyncThreadBudgetResolver.MODE_FIXED.equals(effectiveMode)
        || SyncThreadBudgetResolver.MODE_CPU_RATIO.equals(effectiveMode)) {
      return effectiveMode;
    }
    throw new BizException("Unsupported sync thread mode: " + effectiveMode);
  }

  private BigDecimal normalizeSyncThreadValue(
      String mode,
      BigDecimal nextValue,
      BigDecimal currentValue) {
    BigDecimal effectiveValue =
        nextValue == null
            ? currentValue
            : nextValue;
    if (effectiveValue == null) {
      effectiveValue =
          SyncThreadBudgetResolver.MODE_CPU_RATIO.equals(mode)
              ? SyncThreadBudgetResolver.DEFAULT_CPU_RATIO_VALUE
              : SyncThreadBudgetResolver.DEFAULT_FIXED_THREAD_VALUE;
    }
    if (effectiveValue.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BizException("Sync thread value must be greater than 0");
    }
    if (SyncThreadBudgetResolver.MODE_CPU_RATIO.equals(mode) && effectiveValue.compareTo(BigDecimal.ONE) > 0) {
      throw new BizException("CPU ratio thread value must be between 0 and 1");
    }
    if (SyncThreadBudgetResolver.MODE_FIXED.equals(mode)
        && effectiveValue.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
      throw new BizException("Fixed thread value must be an integer");
    }
    return effectiveValue;
  }

  private Integer normalizeMaxSyncThreads(Integer nextMaxThreads, Integer currentMaxThreads) {
    Integer effectiveMaxThreads = nextMaxThreads == null ? currentMaxThreads : nextMaxThreads;
    if (effectiveMaxThreads == null) {
      effectiveMaxThreads = properties.getMaxSyncThreads();
    }
    if (effectiveMaxThreads < 1) {
      throw new BizException("Max sync threads must be greater than 0");
    }
    return effectiveMaxThreads;
  }

  private String resolveSecret(String nextValue, String currentValue) {
    if (nextValue == null || nextValue.isBlank()) {
      return currentValue == null ? "" : currentValue;
    }
    return nextValue;
  }

  private void validateSourceInstance(String sourceInstance) {
    if (sourceInstance != null
        && sourceInstance.length() > GitlabSourceInstanceSupport.MAX_SOURCE_INSTANCE_LENGTH) {
      throw new BizException(
          "GitLab 数据源标识长度不能超过 "
              + GitlabSourceInstanceSupport.MAX_SOURCE_INSTANCE_LENGTH
              + " 个字符");
    }
  }

  private void normalizePersistedSourceInstance(GitlabSyncConfig config) {
    config.setSourceInstance(GitlabSourceInstanceSupport.normalizeSourceInstance(config.getSourceInstance()));
    if (config.getSourceEnabled() == null) {
      config.setSourceEnabled(config.isEnabled());
    }
    if (config.getSystemHookEnabled() == null) {
      config.setSystemHookEnabled(config.getSystemHookSecret() != null && !config.getSystemHookSecret().isBlank());
    }
    if (config.getSyncThreadMode() == null || config.getSyncThreadMode().isBlank()) {
      config.setSyncThreadMode(SyncThreadBudgetResolver.MODE_FIXED);
    } else {
      config.setSyncThreadMode(config.getSyncThreadMode().trim().toUpperCase(Locale.ROOT));
      if (!SyncThreadBudgetResolver.MODE_FIXED.equals(config.getSyncThreadMode())
          && !SyncThreadBudgetResolver.MODE_CPU_RATIO.equals(config.getSyncThreadMode())) {
        config.setSyncThreadMode(SyncThreadBudgetResolver.MODE_FIXED);
      }
    }
    if (config.getSyncThreadValue() == null) {
      config.setSyncThreadValue(
          SyncThreadBudgetResolver.MODE_CPU_RATIO.equals(config.getSyncThreadMode())
              ? SyncThreadBudgetResolver.DEFAULT_CPU_RATIO_VALUE
              : SyncThreadBudgetResolver.DEFAULT_FIXED_THREAD_VALUE);
    }
    if (config.getMaxSyncThreads() == null) {
      config.setMaxSyncThreads(Math.max(1, properties.getMaxSyncThreads()));
    }
  }
}
