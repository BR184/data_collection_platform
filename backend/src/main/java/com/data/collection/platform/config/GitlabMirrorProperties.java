package com.data.collection.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gitlab-mirror")
public class GitlabMirrorProperties {
  private boolean schedulerEnabled = true;
  private int schedulerDelayMs = 60000;
  private int runDispatcherDelayMs = 2000;
  private String webBaseUrl = "http://localhost";
  private String systemHookBaseUrl = "http://localhost:18080/api/gitlab-sync/system-hook";
  private String dockerCommand = "docker";
  private int heartbeatTimeoutSeconds = 180;
  private int dedupeWindowSeconds = 15;
  private int failureBackoffMinutes = 10;
  private int schemaCheckIntervalMinutes = 720;
  private int systemHookStatusCacheSeconds = 60;
  private int systemHookBatchWindowSeconds = 3;
  private int systemHookBatchSize = 10;
  private int systemHookMaxQueueSize = 1000;
  private int externalQueryTimeoutSeconds = 120;
  private int externalQueryRetryAttempts = 3;
  private int externalQueryRetryDelayMs = 1000;
  private int externalQueryRetryMaxDelayMs = 30000;
  private int incrementalLookbackMinutes = 5;

  public boolean isSchedulerEnabled() {
    return schedulerEnabled;
  }

  public void setSchedulerEnabled(boolean schedulerEnabled) {
    this.schedulerEnabled = schedulerEnabled;
  }

  public int getSchedulerDelayMs() {
    return schedulerDelayMs;
  }

  public void setSchedulerDelayMs(int schedulerDelayMs) {
    this.schedulerDelayMs = schedulerDelayMs;
  }

  public int getRunDispatcherDelayMs() {
    return runDispatcherDelayMs;
  }

  public void setRunDispatcherDelayMs(int runDispatcherDelayMs) {
    this.runDispatcherDelayMs = runDispatcherDelayMs;
  }

  public String getWebBaseUrl() {
    return webBaseUrl;
  }

  public void setWebBaseUrl(String webBaseUrl) {
    this.webBaseUrl = webBaseUrl;
  }

  public String getSystemHookBaseUrl() {
    return systemHookBaseUrl;
  }

  public void setSystemHookBaseUrl(String systemHookBaseUrl) {
    this.systemHookBaseUrl = systemHookBaseUrl;
  }

  public String getDockerCommand() {
    return dockerCommand;
  }

  public void setDockerCommand(String dockerCommand) {
    this.dockerCommand = dockerCommand;
  }

  public int getHeartbeatTimeoutSeconds() {
    return heartbeatTimeoutSeconds;
  }

  public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
    this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
  }

  public int getDedupeWindowSeconds() {
    return dedupeWindowSeconds;
  }

  public void setDedupeWindowSeconds(int dedupeWindowSeconds) {
    this.dedupeWindowSeconds = dedupeWindowSeconds;
  }

  public int getFailureBackoffMinutes() {
    return failureBackoffMinutes;
  }

  public void setFailureBackoffMinutes(int failureBackoffMinutes) {
    this.failureBackoffMinutes = failureBackoffMinutes;
  }

  public int getSchemaCheckIntervalMinutes() {
    return schemaCheckIntervalMinutes;
  }

  public void setSchemaCheckIntervalMinutes(int schemaCheckIntervalMinutes) {
    this.schemaCheckIntervalMinutes = schemaCheckIntervalMinutes;
  }

  public int getSystemHookStatusCacheSeconds() {
    return systemHookStatusCacheSeconds;
  }

  public void setSystemHookStatusCacheSeconds(int systemHookStatusCacheSeconds) {
    this.systemHookStatusCacheSeconds = systemHookStatusCacheSeconds;
  }

  public int getSystemHookBatchWindowSeconds() {
    return systemHookBatchWindowSeconds;
  }

  public void setSystemHookBatchWindowSeconds(int systemHookBatchWindowSeconds) {
    this.systemHookBatchWindowSeconds = systemHookBatchWindowSeconds;
  }

  public int getSystemHookBatchSize() {
    return systemHookBatchSize;
  }

  public void setSystemHookBatchSize(int systemHookBatchSize) {
    this.systemHookBatchSize = systemHookBatchSize;
  }

  public int getSystemHookMaxQueueSize() {
    return systemHookMaxQueueSize;
  }

  public void setSystemHookMaxQueueSize(int systemHookMaxQueueSize) {
    this.systemHookMaxQueueSize = systemHookMaxQueueSize;
  }

  public int getExternalQueryTimeoutSeconds() {
    return externalQueryTimeoutSeconds;
  }

  public void setExternalQueryTimeoutSeconds(int externalQueryTimeoutSeconds) {
    this.externalQueryTimeoutSeconds = externalQueryTimeoutSeconds;
  }

  public int getExternalQueryRetryAttempts() {
    return externalQueryRetryAttempts;
  }

  public void setExternalQueryRetryAttempts(int externalQueryRetryAttempts) {
    this.externalQueryRetryAttempts = externalQueryRetryAttempts;
  }

  public int getExternalQueryRetryDelayMs() {
    return externalQueryRetryDelayMs;
  }

  public void setExternalQueryRetryDelayMs(int externalQueryRetryDelayMs) {
    this.externalQueryRetryDelayMs = externalQueryRetryDelayMs;
  }

  public int getExternalQueryRetryMaxDelayMs() {
    return externalQueryRetryMaxDelayMs;
  }

  public void setExternalQueryRetryMaxDelayMs(int externalQueryRetryMaxDelayMs) {
    this.externalQueryRetryMaxDelayMs = externalQueryRetryMaxDelayMs;
  }

  public int getIncrementalLookbackMinutes() {
    return incrementalLookbackMinutes;
  }

  public void setIncrementalLookbackMinutes(int incrementalLookbackMinutes) {
    this.incrementalLookbackMinutes = incrementalLookbackMinutes;
  }
}
