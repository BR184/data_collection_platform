package com.data.collection.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gitlab-mirror")
public class GitlabMirrorProperties {
  private boolean schedulerEnabled = true;
  private int schedulerDelayMs = 60000;
  private String webBaseUrl = "http://172.22.10.233";
  private String webhookBaseUrl = "http://localhost:18080/api/gitlab-sync/webhook";
  private String dockerCommand = "docker";
  private int heartbeatTimeoutSeconds = 180;
  private int dedupeWindowSeconds = 15;
  private int failureBackoffMinutes = 10;
  private int schemaCheckIntervalMinutes = 720;
  private int webhookStatusCacheSeconds = 60;
  private int webhookBatchWindowSeconds = 3;
  private int webhookBatchSize = 10;

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

  public String getWebBaseUrl() {
    return webBaseUrl;
  }

  public void setWebBaseUrl(String webBaseUrl) {
    this.webBaseUrl = webBaseUrl;
  }

  public String getWebhookBaseUrl() {
    return webhookBaseUrl;
  }

  public void setWebhookBaseUrl(String webhookBaseUrl) {
    this.webhookBaseUrl = webhookBaseUrl;
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

  public int getWebhookStatusCacheSeconds() {
    return webhookStatusCacheSeconds;
  }

  public void setWebhookStatusCacheSeconds(int webhookStatusCacheSeconds) {
    this.webhookStatusCacheSeconds = webhookStatusCacheSeconds;
  }

  public int getWebhookBatchWindowSeconds() {
    return webhookBatchWindowSeconds;
  }

  public void setWebhookBatchWindowSeconds(int webhookBatchWindowSeconds) {
    this.webhookBatchWindowSeconds = webhookBatchWindowSeconds;
  }

  public int getWebhookBatchSize() {
    return webhookBatchSize;
  }

  public void setWebhookBatchSize(int webhookBatchSize) {
    this.webhookBatchSize = webhookBatchSize;
  }
}
