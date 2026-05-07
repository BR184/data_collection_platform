package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabSyncConfigMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitlabConfigService {
  static final int MIN_COMPENSATION_INTERVAL_MINUTES = 1;
  static final int MAX_COMPENSATION_INTERVAL_MINUTES = 720;

  private final GitlabSyncConfigMapper configMapper;

  public GitlabConfigService(GitlabSyncConfigMapper configMapper) {
    this.configMapper = configMapper;
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
      return defaultConfig();
    }
    normalizePersistedSourceInstance(config);
    return config;
  }

  public GitlabSyncConfig getConfigForWebhook(String secret) {
    List<GitlabSyncConfig> configs =
        configMapper.selectList(new LambdaQueryWrapper<GitlabSyncConfig>()
            .eq(GitlabSyncConfig::isEnabled, true)
            .orderByAsc(GitlabSyncConfig::getId));
    if (configs == null || configs.isEmpty()) {
      return defaultConfig();
    }
    configs.forEach(this::normalizePersistedSourceInstance);
    if (secret != null && !secret.isBlank()) {
      List<GitlabSyncConfig> matches = configs.stream()
          .filter(config -> config.getWebhookSecret() != null && !config.getWebhookSecret().isBlank())
          .filter(config -> config.getWebhookSecret().equals(secret))
          .toList();
      if (matches.size() == 1) {
        return matches.getFirst();
      }
    }
    if (configs.size() == 1) {
      return configs.getFirst();
    }
    throw new BizException("存在多个 GitLab 数据源，请为每个源配置不同的 Webhook Secret");
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
    config.setEnabled(true);
    config.setSourceInstance(GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance));
    config.setAutoSyncEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setWhitelistTables(List.of());
    config.setDbHost("localhost");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("");
    config.setDockerContainerName("gitlab-data-web-1");
    config.setCompensationIntervalMinutes(10);
    return config;
  }

  private GitlabSyncConfig normalize(GitlabSyncConfig config, GitlabSyncConfig current) {
    GitlabSyncConfig normalized = new GitlabSyncConfig();
    boolean syncEnabled = config.isAutoSyncEnabled();
    String sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(config.getSourceInstance());
    validateSourceInstance(sourceInstance);
    normalized.setName(config.getName());
    normalized.setEnabled(syncEnabled);
    normalized.setSourceInstance(sourceInstance);
    normalized.setAutoSyncEnabled(syncEnabled);
    normalized.setSourceMode(config.getSourceMode() == null ? SourceMode.DOCKER : config.getSourceMode());
    normalized.setWhitelistMode(config.getWhitelistMode() == null ? WhitelistMode.RECOMMENDED : config.getWhitelistMode());
    normalized.setWhitelistTables(config.getWhitelistTables() == null ? List.of() : config.getWhitelistTables());
    normalized.setDbHost(config.getDbHost());
    normalized.setDbPort(config.getDbPort());
    normalized.setDbName(config.getDbName());
    normalized.setDbUsername(config.getDbUsername());
    normalized.setDbPassword(resolveSecret(config.getDbPassword(), current.getDbPassword()));
    normalized.setDockerContainerName(config.getDockerContainerName());
    normalized.setWebhookSecret(resolveSecret(config.getWebhookSecret(), current.getWebhookSecret()));
    normalized.setWebhookProjectId(config.getWebhookProjectId());
    normalized.setCompensationIntervalMinutes(normalizeCompensationInterval(config.getCompensationIntervalMinutes()));
    return normalized;
  }

  private Integer normalizeCompensationInterval(Integer intervalMinutes) {
    int effectiveValue = intervalMinutes == null ? 10 : intervalMinutes;
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
  }
}
