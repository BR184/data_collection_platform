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
    return config == null ? defaultConfig() : config;
  }

  public GitlabSyncConfig getConfigById(Long id) {
    if (id == null) {
      return defaultConfig();
    }
    GitlabSyncConfig config = configMapper.selectById(id);
    return config == null ? defaultConfig() : config;
  }

  public GitlabSyncConfig saveConfig(GitlabSyncConfig input) {
    GitlabSyncConfig current = getConfig();
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
    return getConfig();
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

  private GitlabSyncConfig defaultConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setName("GitLab 默认数据源");
    config.setEnabled(true);
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
    normalized.setName(config.getName());
    normalized.setEnabled(syncEnabled);
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
}
