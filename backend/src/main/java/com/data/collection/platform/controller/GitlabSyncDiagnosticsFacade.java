package com.data.collection.platform.controller;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSystemHookRegistrationStatus;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.GitlabSystemHookRegistrationService;
import com.data.collection.platform.service.GitlabWhitelistService;
import com.data.collection.platform.service.SourceMetadataInspector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GitlabSyncDiagnosticsFacade {
  private final GitlabConfigService configService;
  private final GitlabMirrorSyncService syncService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabMirrorProperties properties;
  private final GitlabSystemHookRegistrationService systemHookRegistrationService;
  private final SourceMetadataInspector sourceMetadataInspector;

  public GitlabSyncDiagnosticsFacade(
      GitlabConfigService configService,
      GitlabMirrorSyncService syncService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorProperties properties,
      GitlabSystemHookRegistrationService systemHookRegistrationService,
      SourceMetadataInspector sourceMetadataInspector) {
    this.configService = configService;
    this.syncService = syncService;
    this.whitelistService = whitelistService;
    this.properties = properties;
    this.systemHookRegistrationService = systemHookRegistrationService;
    this.sourceMetadataInspector = sourceMetadataInspector;
  }

  public GitlabSyncDiagnosticsResponse diagnose(GitlabSyncConfig config, boolean defaultConfig) {
    ConnectionDiagnostics connectionDiagnostics = diagnoseConnection(config, defaultConfig);
    WhitelistDiagnostics whitelistDiagnostics = diagnoseWhitelist(config);
    GitlabSourceMetadataDiagnosticsResponse metadataDiagnostics =
        diagnoseMetadata(config, whitelistDiagnostics.options());
    GitlabSystemHookRegistrationStatus systemHookStatus = diagnoseSystemHookRegistration(config);
    SystemHookConfigDiagnostics systemHookConfigDiagnostics = diagnoseSystemHookConfig(config);

    return new GitlabSyncDiagnosticsResponse(
        config.getId(),
        GitlabSourceInstanceSupport.sourceInstanceOf(config),
        config.getSourceMode(),
        connectionDiagnostics.ok(),
        connectionDiagnostics.message(),
        whitelistDiagnostics.ok(),
        whitelistDiagnostics.message(),
        whitelistDiagnostics.optionCount(),
        metadataDiagnostics.metadataOk(),
        metadataDiagnostics.metadataMessage(),
        metadataDiagnostics.sourceTableCount(),
        metadataDiagnostics.primaryKeyTableCount(),
        metadataDiagnostics.missingPrimaryKeyTableCount(),
        metadataDiagnostics.missingUpdatedAtTableCount(),
        metadataDiagnostics.sourceTables(),
        properties.getSystemHookBaseUrl(),
        Boolean.TRUE.equals(config.getSystemHookEnabled()),
        systemHookConfigDiagnostics.secretConfigured(),
        systemHookConfigDiagnostics.secretUnique(),
        systemHookConfigDiagnostics.message(),
        systemHookStatus.supported(),
        systemHookStatus.registered(),
        systemHookStatus.message(),
        diagnoseRuntimeConfig());
  }

  private ConnectionDiagnostics diagnoseConnection(GitlabSyncConfig config, boolean defaultConfig) {
    try {
      if (defaultConfig) {
        syncService.testConnection();
      } else {
        syncService.testConnection(config.getId());
      }
      return new ConnectionDiagnostics(true, "GitLab PostgreSQL 连接成功");
    } catch (Exception e) {
      return new ConnectionDiagnostics(false, e.getMessage());
    }
  }

  private WhitelistDiagnostics diagnoseWhitelist(GitlabSyncConfig config) {
    try {
      List<TableWhitelistOption> options = whitelistService.listOptionsStrict(config);
      return new WhitelistDiagnostics(true, "GitLab 白名单选项已加载", options.size(), options);
    } catch (Exception e) {
      return new WhitelistDiagnostics(false, e.getMessage(), 0, List.of());
    }
  }

  private GitlabSourceMetadataDiagnosticsResponse diagnoseMetadata(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions) {
    try {
      return sourceMetadataInspector.inspectSourceMetadata(config, whitelistOptions);
    } catch (Exception e) {
      return GitlabSourceMetadataDiagnosticsResponse.failure(e.getMessage());
    }
  }

  private GitlabSystemHookRegistrationStatus diagnoseSystemHookRegistration(GitlabSyncConfig config) {
    try {
      return systemHookRegistrationService.getStatus(config, properties.getSystemHookBaseUrl());
    } catch (Exception e) {
      return new GitlabSystemHookRegistrationStatus(
          false,
          false,
          false,
          config.getSystemHookProjectId(),
          properties.getSystemHookBaseUrl(),
          e.getMessage(),
          List.of());
    }
  }

  private SystemHookConfigDiagnostics diagnoseSystemHookConfig(GitlabSyncConfig config) {
    boolean systemHookEnabled = Boolean.TRUE.equals(config.getSystemHookEnabled());
    boolean secretConfigured = config.getSystemHookSecret() != null && !config.getSystemHookSecret().isBlank();
    boolean secretUnique = true;
    if (systemHookEnabled && secretConfigured) {
      String secret = config.getSystemHookSecret();
      List<GitlabSyncConfig> configs = configService.listConfigs();
      secretUnique = (configs == null ? List.<GitlabSyncConfig>of() : configs).stream()
          .filter(candidate -> candidate.getId() != null)
          .filter(candidate -> !candidate.getId().equals(config.getId()))
          .filter(candidate -> Boolean.TRUE.equals(candidate.getSourceEnabled()))
          .filter(candidate -> Boolean.TRUE.equals(candidate.getSystemHookEnabled()))
          .noneMatch(candidate -> secret.equals(candidate.getSystemHookSecret()));
    }
    String message;
    if (!systemHookEnabled) {
      message = "System Hook 接收器未启用";
    } else if (!secretConfigured) {
      message = "System Hook 需要配置唯一密钥";
    } else if (!secretUnique) {
      message = "System Hook 密钥已被其他 GitLab 数据源使用";
    } else {
      message = "System Hook 配置可用";
    }
    return new SystemHookConfigDiagnostics(secretConfigured, secretUnique, message);
  }

  private List<String> diagnoseRuntimeConfig() {
    List<String> warnings = new ArrayList<>();
    int heartbeatTimeoutSeconds = properties.getHeartbeatTimeoutSeconds();
    int queryTimeoutSeconds = properties.getExternalQueryTimeoutSeconds();
    int minimumRecommendedSeconds = queryTimeoutSeconds + 30;
    if (heartbeatTimeoutSeconds <= queryTimeoutSeconds) {
      warnings.add("心跳超时时间必须大于外部查询超时时间");
    } else if (heartbeatTimeoutSeconds <= minimumRecommendedSeconds) {
      warnings.add("心跳超时时间接近外部查询超时时间，长时间写入可能被误判为过期任务");
    }
    return warnings;
  }

  private record ConnectionDiagnostics(boolean ok, String message) {}

  private record WhitelistDiagnostics(
      boolean ok,
      String message,
      int optionCount,
      List<TableWhitelistOption> options) {}

  private record SystemHookConfigDiagnostics(
      boolean secretConfigured,
      boolean secretUnique,
      String message) {}
}
