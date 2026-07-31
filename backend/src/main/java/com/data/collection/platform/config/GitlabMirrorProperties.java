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
  private int maxRunDurationMinutes = 720;
  private boolean cancelCompensationRunsAtDayBoundary = true;
  private int dedupeWindowSeconds = 15;
  private int failureBackoffMinutes = 10;
  private int schemaCheckIntervalMinutes = 720;
  private int systemHookStatusCacheSeconds = 60;
  private int systemHookBatchWindowSeconds = 3;
  private int systemHookBatchSize = 10;
  private int systemHookMaxQueueSize = 1000;
  private int recentLogsLimit = 100;
  private int externalQueryTimeoutSeconds = 120;
  private int externalQueryRetryAttempts = 3;
  private int externalQueryRetryDelayMs = 1000;
  private int externalQueryRetryMaxDelayMs = 30000;
  private int interactiveConnectionTimeoutSeconds = 5;
  private int sourceFailureCacheSeconds = 60;
  private int maxConcurrentConnectionTests = 4;
  private int incrementalLookbackMinutes = 5;
  private int maxSyncThreads = 16;
  private int directPoolControlConnectionReserve = 1;
  private int directPoolAcquireTimeoutSeconds = 30;
  private int tableTaskLeaseSeconds = 180;
  private int maxContinuationTasksPerTable = 50000;
  private int customerIssueDelayCheckDelayMs = 3600000;
  private boolean customerIssueDelayPreWritebackSyncEnabled = true;
  private int customerIssueDelayPreWritebackSyncTimeoutSeconds = 180;
  private String customerIssueDelayPreWritebackSyncTables = "issues,notes,label_links,labels";
  private boolean customerIssueDelayWritebackWorkerEnabled = true;
  private int customerIssueDelayWritebackWorkerDelayMs = 5000;
  private int customerIssueDelayWritebackLeaseSeconds = 120;
  private boolean codeReviewMetricEnrichmentEnabled = true;
  private int codeReviewMetricEnrichmentDelayMs = 2000;
  private int codeReviewMetricEnrichmentInitialDelayMs = 30000;
  private int codeReviewMetricConcurrency = 4;
  private int codeReviewMetricBatchSize = 20;
  private int codeReviewMetricHistoricalScanSize = 200;
  private int codeReviewMetricRequestTimeoutSeconds = 30;
  private int codeReviewMetricRetryBaseSeconds = 30;

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

  public int getMaxRunDurationMinutes() {
    return maxRunDurationMinutes;
  }

  public void setMaxRunDurationMinutes(int maxRunDurationMinutes) {
    this.maxRunDurationMinutes = maxRunDurationMinutes;
  }

  public boolean isCancelCompensationRunsAtDayBoundary() {
    return cancelCompensationRunsAtDayBoundary;
  }

  public void setCancelCompensationRunsAtDayBoundary(boolean cancelCompensationRunsAtDayBoundary) {
    this.cancelCompensationRunsAtDayBoundary = cancelCompensationRunsAtDayBoundary;
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

  public int getRecentLogsLimit() {
    return recentLogsLimit;
  }

  public void setRecentLogsLimit(int recentLogsLimit) {
    this.recentLogsLimit = recentLogsLimit;
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

  public int getInteractiveConnectionTimeoutSeconds() {
    return interactiveConnectionTimeoutSeconds;
  }

  public void setInteractiveConnectionTimeoutSeconds(int interactiveConnectionTimeoutSeconds) {
    this.interactiveConnectionTimeoutSeconds = interactiveConnectionTimeoutSeconds;
  }

  public int getSourceFailureCacheSeconds() {
    return sourceFailureCacheSeconds;
  }

  public void setSourceFailureCacheSeconds(int sourceFailureCacheSeconds) {
    this.sourceFailureCacheSeconds = sourceFailureCacheSeconds;
  }

  public int getMaxConcurrentConnectionTests() {
    return maxConcurrentConnectionTests;
  }

  public void setMaxConcurrentConnectionTests(int maxConcurrentConnectionTests) {
    this.maxConcurrentConnectionTests = maxConcurrentConnectionTests;
  }

  public int getIncrementalLookbackMinutes() {
    return incrementalLookbackMinutes;
  }

  public void setIncrementalLookbackMinutes(int incrementalLookbackMinutes) {
    this.incrementalLookbackMinutes = incrementalLookbackMinutes;
  }

  public int getMaxSyncThreads() {
    return maxSyncThreads;
  }

  public void setMaxSyncThreads(int maxSyncThreads) {
    this.maxSyncThreads = maxSyncThreads;
  }

  public int getDirectPoolControlConnectionReserve() {
    return directPoolControlConnectionReserve;
  }

  public void setDirectPoolControlConnectionReserve(int directPoolControlConnectionReserve) {
    this.directPoolControlConnectionReserve = directPoolControlConnectionReserve;
  }

  public int getDirectPoolAcquireTimeoutSeconds() {
    return directPoolAcquireTimeoutSeconds;
  }

  public void setDirectPoolAcquireTimeoutSeconds(int directPoolAcquireTimeoutSeconds) {
    this.directPoolAcquireTimeoutSeconds = directPoolAcquireTimeoutSeconds;
  }

  public int getTableTaskLeaseSeconds() {
    return tableTaskLeaseSeconds;
  }

  public void setTableTaskLeaseSeconds(int tableTaskLeaseSeconds) {
    this.tableTaskLeaseSeconds = tableTaskLeaseSeconds;
  }

  public int getMaxContinuationTasksPerTable() {
    return maxContinuationTasksPerTable;
  }

  public void setMaxContinuationTasksPerTable(int maxContinuationTasksPerTable) {
    this.maxContinuationTasksPerTable = maxContinuationTasksPerTable;
  }

  public int getCustomerIssueDelayCheckDelayMs() {
    return customerIssueDelayCheckDelayMs;
  }

  public void setCustomerIssueDelayCheckDelayMs(int customerIssueDelayCheckDelayMs) {
    this.customerIssueDelayCheckDelayMs = customerIssueDelayCheckDelayMs;
  }

  public boolean isCustomerIssueDelayPreWritebackSyncEnabled() {
    return customerIssueDelayPreWritebackSyncEnabled;
  }

  public void setCustomerIssueDelayPreWritebackSyncEnabled(boolean customerIssueDelayPreWritebackSyncEnabled) {
    this.customerIssueDelayPreWritebackSyncEnabled = customerIssueDelayPreWritebackSyncEnabled;
  }

  public int getCustomerIssueDelayPreWritebackSyncTimeoutSeconds() {
    return customerIssueDelayPreWritebackSyncTimeoutSeconds;
  }

  public void setCustomerIssueDelayPreWritebackSyncTimeoutSeconds(int customerIssueDelayPreWritebackSyncTimeoutSeconds) {
    this.customerIssueDelayPreWritebackSyncTimeoutSeconds = customerIssueDelayPreWritebackSyncTimeoutSeconds;
  }

  public String getCustomerIssueDelayPreWritebackSyncTables() {
    return customerIssueDelayPreWritebackSyncTables;
  }

  public void setCustomerIssueDelayPreWritebackSyncTables(String customerIssueDelayPreWritebackSyncTables) {
    this.customerIssueDelayPreWritebackSyncTables = customerIssueDelayPreWritebackSyncTables;
  }

  public boolean isCustomerIssueDelayWritebackWorkerEnabled() {
    return customerIssueDelayWritebackWorkerEnabled;
  }

  public void setCustomerIssueDelayWritebackWorkerEnabled(boolean customerIssueDelayWritebackWorkerEnabled) {
    this.customerIssueDelayWritebackWorkerEnabled = customerIssueDelayWritebackWorkerEnabled;
  }

  public int getCustomerIssueDelayWritebackWorkerDelayMs() {
    return customerIssueDelayWritebackWorkerDelayMs;
  }

  public void setCustomerIssueDelayWritebackWorkerDelayMs(int customerIssueDelayWritebackWorkerDelayMs) {
    this.customerIssueDelayWritebackWorkerDelayMs = customerIssueDelayWritebackWorkerDelayMs;
  }

  public int getCustomerIssueDelayWritebackLeaseSeconds() {
    return customerIssueDelayWritebackLeaseSeconds;
  }

  public void setCustomerIssueDelayWritebackLeaseSeconds(int customerIssueDelayWritebackLeaseSeconds) {
    this.customerIssueDelayWritebackLeaseSeconds = customerIssueDelayWritebackLeaseSeconds;
  }

  public boolean isCodeReviewMetricEnrichmentEnabled() {
    return codeReviewMetricEnrichmentEnabled;
  }

  public void setCodeReviewMetricEnrichmentEnabled(boolean codeReviewMetricEnrichmentEnabled) {
    this.codeReviewMetricEnrichmentEnabled = codeReviewMetricEnrichmentEnabled;
  }

  public int getCodeReviewMetricEnrichmentDelayMs() {
    return codeReviewMetricEnrichmentDelayMs;
  }

  public void setCodeReviewMetricEnrichmentDelayMs(int codeReviewMetricEnrichmentDelayMs) {
    this.codeReviewMetricEnrichmentDelayMs = codeReviewMetricEnrichmentDelayMs;
  }

  public int getCodeReviewMetricEnrichmentInitialDelayMs() {
    return codeReviewMetricEnrichmentInitialDelayMs;
  }

  public void setCodeReviewMetricEnrichmentInitialDelayMs(int codeReviewMetricEnrichmentInitialDelayMs) {
    this.codeReviewMetricEnrichmentInitialDelayMs = codeReviewMetricEnrichmentInitialDelayMs;
  }

  public int getCodeReviewMetricConcurrency() {
    return codeReviewMetricConcurrency;
  }

  public void setCodeReviewMetricConcurrency(int codeReviewMetricConcurrency) {
    this.codeReviewMetricConcurrency = codeReviewMetricConcurrency;
  }

  public int getCodeReviewMetricBatchSize() {
    return codeReviewMetricBatchSize;
  }

  public void setCodeReviewMetricBatchSize(int codeReviewMetricBatchSize) {
    this.codeReviewMetricBatchSize = codeReviewMetricBatchSize;
  }

  public int getCodeReviewMetricHistoricalScanSize() {
    return codeReviewMetricHistoricalScanSize;
  }

  public void setCodeReviewMetricHistoricalScanSize(int codeReviewMetricHistoricalScanSize) {
    this.codeReviewMetricHistoricalScanSize = codeReviewMetricHistoricalScanSize;
  }

  public int getCodeReviewMetricRequestTimeoutSeconds() {
    return codeReviewMetricRequestTimeoutSeconds;
  }

  public void setCodeReviewMetricRequestTimeoutSeconds(int codeReviewMetricRequestTimeoutSeconds) {
    this.codeReviewMetricRequestTimeoutSeconds = codeReviewMetricRequestTimeoutSeconds;
  }

  public int getCodeReviewMetricRetryBaseSeconds() {
    return codeReviewMetricRetryBaseSeconds;
  }

  public void setCodeReviewMetricRetryBaseSeconds(int codeReviewMetricRetryBaseSeconds) {
    this.codeReviewMetricRetryBaseSeconds = codeReviewMetricRetryBaseSeconds;
  }
}
