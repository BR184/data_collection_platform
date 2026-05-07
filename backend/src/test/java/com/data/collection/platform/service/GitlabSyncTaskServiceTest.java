package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabSyncTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class GitlabSyncTaskServiceTest {

  @Autowired
  private GitlabSyncTaskService taskService;

  @Autowired
  private GitlabConfigService configService;

  @Autowired
  private GitlabSyncTaskMapper taskMapper;

  @Autowired
  private GitlabSyncLogService logService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from gitlab_webhook_events");
    jdbcTemplate.update("delete from gitlab_sync_logs");
    jdbcTemplate.update("delete from gitlab_mirror_records");
    jdbcTemplate.update("delete from gitlab_sync_tasks");
    jdbcTemplate.update("delete from gitlab_sync_configs");
  }

  @Test
  void sameManualTaskShouldBeDeduplicatedByScope() {
    GitlabSyncConfig config = configService.saveConfig(baseConfig());

    GitlabSyncTask first = taskService.submitTask(config, SyncType.FULL, SyncTriggerType.MANUAL, "Manual full sync", Map.of());
    GitlabSyncTask second = taskService.submitTask(config, SyncType.FULL, SyncTriggerType.MANUAL, "Manual full sync", Map.of());

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(taskMapper.selectCount(null)).isEqualTo(1);
  }

  @Test
  void differentTaskTypeWhileOneIsActiveShouldCreateQueuedTask() {
    GitlabSyncConfig config = configService.saveConfig(baseConfig());
    GitlabSyncTask first = taskService.submitTask(config, SyncType.FULL, SyncTriggerType.MANUAL, "Manual full sync", Map.of());
    taskService.claimPendingTask(first.getId(), "tester");

    GitlabSyncTask queued = taskService.submitTask(
        config,
        SyncType.INCREMENTAL,
        SyncTriggerType.WEBHOOK,
        "Triggered by webhook",
        Map.of("event", "Issue Hook"));

    assertThat(queued.getStatus()).isEqualTo(SyncStatus.QUEUED);
    assertThat(taskMapper.selectCount(null)).isEqualTo(2);
  }

  @Test
  void webhookTasksWithDifferentPayloadShouldQueueInsteadOfBeingReused() {
    GitlabSyncConfig config = configService.saveConfig(baseConfig());
    GitlabSyncTask first = taskService.submitTask(
        config,
        SyncType.WEBHOOK,
        SyncTriggerType.WEBHOOK,
        "Triggered by webhook: issue",
        Map.of("eventType", "Issue Hook", "webhookPayload", Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101))));
    taskService.claimPendingTask(first.getId(), "tester");

    GitlabSyncTask second = taskService.submitTask(
        config,
        SyncType.WEBHOOK,
        SyncTriggerType.WEBHOOK,
        "Triggered by webhook: note",
        Map.of("eventType", "Note Hook", "webhookPayload", Map.of("object_kind", "note", "object_attributes", Map.of("id", 202))));

    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(second.getStatus()).isEqualTo(SyncStatus.QUEUED);
    assertThat(taskMapper.selectCount(null)).isEqualTo(2);
  }

  @Test
  void scopeKeyShouldSeparateDifferentSourceInstances() {
    GitlabSyncConfig ccConfig = baseConfig();
    ccConfig.setId(1L);
    ccConfig.setSourceInstance("cc");
    GitlabSyncConfig dgmConfig = baseConfig();
    dgmConfig.setId(1L);
    dgmConfig.setSourceInstance("dgm");

    assertThat(taskService.buildScopeKey(ccConfig)).isNotEqualTo(taskService.buildScopeKey(dgmConfig));
    assertThat(taskService.buildScopeKey(ccConfig)).contains("source-cc");
    assertThat(taskService.buildScopeKey(dgmConfig)).contains("source-dgm");
  }

  @Test
  void staleRunningTaskShouldBeRecoveredAsTimeout() {
    GitlabSyncConfig config = configService.saveConfig(baseConfig());
    GitlabSyncTask first = taskService.submitTask(config, SyncType.FULL, SyncTriggerType.MANUAL, "Manual full sync", Map.of());
    taskService.claimPendingTask(first.getId(), "tester");

    LocalDateTime staleStartedAt = LocalDateTime.now().minusMinutes(10);
    long logId = logService.start(config.getId(), SyncType.FULL, List.of("issues"), "Manual full sync");
    jdbcTemplate.update(
        "update gitlab_sync_logs set started_at = ? where id = ?",
        staleStartedAt.plusSeconds(45),
        logId);
    taskMapper.updateById(markStaleExecutionWindow(taskMapper.selectById(first.getId()), staleStartedAt));

    taskService.recoverTimedOutTasks();

    GitlabSyncTask timedOut = taskMapper.selectById(first.getId());
    GitlabSyncLog recoveredLog = logService.findLatest(config.getId());
    assertThat(timedOut.getStatus()).isEqualTo(SyncStatus.TIMEOUT);
    assertThat(timedOut.getFinishedReason()).isEqualTo("任务心跳超时");
    assertThat(recoveredLog.getStatus()).isEqualTo(SyncStatus.TIMEOUT);
    assertThat(recoveredLog.getMessage()).isEqualTo("任务心跳超时");
  }

  @Test
  void requestCancelShouldClosePendingTaskImmediately() {
    GitlabSyncConfig config = configService.saveConfig(baseConfig());
    GitlabSyncTask pending = taskService.submitTask(config, SyncType.FULL, SyncTriggerType.MANUAL, "Manual full sync", Map.of());

    GitlabSyncTask cancelled = taskService.requestCancelLatest(config.getId());

    assertThat(cancelled).isNotNull();
    assertThat(taskMapper.selectById(pending.getId()).getStatus()).isEqualTo(SyncStatus.CANCELLED);
  }

  private GitlabSyncTask markStaleExecutionWindow(GitlabSyncTask task, LocalDateTime startedAt) {
    task.setStartedAt(startedAt);
    task.setHeartbeatAt(startedAt.plusSeconds(30));
    return task;
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setName("GitLab default source");
    config.setEnabled(true);
    config.setAutoSyncEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setSourceInstance("default");
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(List.of("issues", "notes"));
    config.setDbHost("localhost");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("");
    config.setDockerContainerName("gitlab-data-web-1");
    config.setCompensationIntervalMinutes(60);
    return config;
  }
}
