package com.data.collection.platform.controller;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabWhitelistService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabSyncConfigFacade {
  private final GitlabConfigService configService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabSyncControllerResponseMapper responseMapper;

  public GitlabSyncConfigFacade(
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService,
      GitlabSyncControllerResponseMapper responseMapper) {
    this.configService = configService;
    this.whitelistService = whitelistService;
    this.responseMapper = responseMapper;
  }

  public GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  public List<GitlabSyncConfig> configs() {
    return configService.listConfigs().stream().map(responseMapper::sanitizeConfigForResponse).toList();
  }

  public List<TableWhitelistOption> whitelistOptions(Long configId) {
    return whitelistService.listOptions(resolveConfig(configId));
  }

  public ApiResponse<GitlabSyncConfig> saveConfig(GitlabSyncSaveConfigRequest request) {
    GitlabSyncConfig config = buildConfig(request);
    boolean sourceEnabled = request.sourceEnabled() == null ? request.enabled() : request.sourceEnabled();
    boolean syncEnabled = request.autoSyncEnabled();
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "CONFIG");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Config_Save")) {
      log.info(
          "Saving GitLab sync config, enabled={}, autoSyncEnabled={}, sourceMode={}, whitelistMode={}",
          sourceEnabled,
          syncEnabled,
          request.sourceMode(),
          request.whitelistMode());
    }
    return ApiResponse.success("配置已保存", responseMapper.sanitizeConfigForResponse(configService.saveConfig(config)));
  }

  private GitlabSyncConfig buildConfig(GitlabSyncSaveConfigRequest request) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    boolean sourceEnabled = request.sourceEnabled() == null ? request.enabled() : request.sourceEnabled();
    config.setId(request.id());
    config.setName(request.name());
    config.setEnabled(sourceEnabled);
    config.setSourceEnabled(sourceEnabled);
    config.setAutoSyncEnabled(request.autoSyncEnabled());
    config.setSourceInstance(request.sourceInstance());
    config.setSourceMode(request.sourceMode());
    config.setWhitelistMode(request.whitelistMode());
    config.setWhitelistTables(request.whitelistTables());
    config.setDbHost(request.dbHost());
    config.setDbPort(request.dbPort());
    config.setDbName(request.dbName());
    config.setDbUsername(request.dbUsername());
    config.setDbPassword(request.dbPassword());
    config.setDockerContainerName(request.dockerContainerName());
    config.setSystemHookSecret(request.systemHookSecret());
    config.setSystemHookEnabled(request.systemHookEnabled());
    config.setSystemHookProjectId(request.systemHookProjectId());
    config.setCompensationIntervalMinutes(request.compensationIntervalMinutes());
    config.setFullCompensationEnabled(request.fullCompensationEnabled());
    config.setFullCompensationTime(request.fullCompensationTime());
    config.setSyncThreadMode(request.syncThreadMode());
    config.setSyncThreadValue(request.syncThreadValue());
    config.setMaxSyncThreads(request.maxSyncThreads());
    return config;
  }
}
