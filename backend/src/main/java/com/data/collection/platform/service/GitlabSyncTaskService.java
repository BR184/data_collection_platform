package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTaskSubmissionResult;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.mapper.GitlabSyncTaskMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
// 同步任务服务是镜像调度的闸门，负责去重、排队、复用活动任务和恢复超时任务。
// 业务服务只提交意图，是否真正创建新任务由这里按 scopeKey 和 dedupeKey 决定。
public class GitlabSyncTaskService {
  private static final List<SyncStatus> ACTIVE_STATUSES = List.of(
      SyncStatus.PENDING,
      SyncStatus.QUEUED,
      SyncStatus.RUNNING,
      SyncStatus.CANCELLING);

  private final GitlabSyncTaskMapper taskMapper;
  private final GitlabMirrorProperties properties;
  private final JsonUtils jsonUtils;
  private final GitlabConfigService configService;
  private final GitlabSyncLogService logService;

  public GitlabSyncTask submitTask(
      GitlabSyncConfig config,
      SyncType taskType,
      SyncTriggerType triggerType,
      String message,
      Map<String, Object> payload) {
    return submitTaskResult(config, taskType, triggerType, message, payload).task();
  }

  public SyncTaskSubmissionResult submitTaskResult(
      GitlabSyncConfig config,
      SyncType taskType,
      SyncTriggerType triggerType,
      String message,
      Map<String, Object> payload) {
    recoverTimedOutTasks();

    String scopeKey = buildScopeKey(config);
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, taskType.name(), scopeKey);
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Submit")) {
      String dedupeKey = buildDedupeKey(config, taskType, triggerType, payload);
      GitlabSyncTask recentDuplicate = findRecentByDedupe(dedupeKey);
      if (recentDuplicate != null) {
        log.info(
            "Task submit deduplicated, existingTaskId={}, triggerType={}, status={}",
            recentDuplicate.getId(),
            triggerType,
            recentDuplicate.getStatus());
        return new SyncTaskSubmissionResult(recentDuplicate, SyncSubmissionAction.DEDUPED);
      }

      GitlabSyncTask activeTask = findActiveByScope(scopeKey);
      if (activeTask != null) {
        boolean preserveWebhookPayload = taskType == SyncType.WEBHOOK;
        if (!preserveWebhookPayload && activeTask.getTaskType() == taskType) {
          log.info(
              "Task submit reused active task, existingTaskId={}, taskType={}, status={}",
              activeTask.getId(),
              taskType,
              activeTask.getStatus());
          return new SyncTaskSubmissionResult(activeTask, SyncSubmissionAction.REUSED_ACTIVE);
        }
        GitlabSyncTask queuedTask = findLatestQueued(scopeKey);
        if (!preserveWebhookPayload && queuedTask != null) {
          log.info(
              "Task submit reused queued task, existingTaskId={}, taskType={}, status={}",
              queuedTask.getId(),
              queuedTask.getTaskType(),
              queuedTask.getStatus());
          return new SyncTaskSubmissionResult(queuedTask, SyncSubmissionAction.REUSED_QUEUED);
        }
        GitlabSyncTask queued = buildTask(
            config,
            taskType,
            triggerType,
            scopeKey,
            dedupeKey,
            SyncStatus.QUEUED,
            payloadWithMessage(message, payload));
        queued.setQueuedAt(LocalDateTime.now());
        queued.setRunAfter(LocalDateTime.now());
        taskMapper.insert(queued);
        log.info(
            "Task queued because active task exists, taskId={}, activeTaskId={}, taskType={}, triggerType={}",
            queued.getId(),
            activeTask.getId(),
            taskType,
            triggerType);
        return new SyncTaskSubmissionResult(queued, SyncSubmissionAction.QUEUED);
      }

      GitlabSyncTask pending = buildTask(
          config,
          taskType,
          triggerType,
          scopeKey,
          dedupeKey,
          SyncStatus.PENDING,
          payloadWithMessage(message, payload));
      taskMapper.insert(pending);
      log.info(
          "Task created and pending, taskId={}, taskType={}, triggerType={}, scope={}",
          pending.getId(),
          taskType,
          triggerType,
          scopeKey);
      return new SyncTaskSubmissionResult(pending, SyncSubmissionAction.CREATED);
    }
  }

  public GitlabSyncTask findDisplayTask(Long configId) {
    if (configId == null) {
      return null;
    }
    GitlabSyncTask active = taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getConfigId, configId)
        .in(GitlabSyncTask::getStatus, ACTIVE_STATUSES)
        .orderByDesc(GitlabSyncTask::getCreatedAt)
        .last("limit 1"));
    if (active != null) {
      return active;
    }
    return taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getConfigId, configId)
        .orderByDesc(GitlabSyncTask::getCreatedAt)
        .last("limit 1"));
  }

  public GitlabSyncTask claimPendingTask(Long taskId, String lockOwner) {
    LocalDateTime now = LocalDateTime.now();
    int updated = taskMapper.update(
        null,
        new LambdaUpdateWrapper<GitlabSyncTask>()
            .eq(GitlabSyncTask::getId, taskId)
            .eq(GitlabSyncTask::getStatus, SyncStatus.PENDING)
            .set(GitlabSyncTask::getStatus, SyncStatus.RUNNING)
            .set(GitlabSyncTask::getLockOwner, lockOwner)
            .set(GitlabSyncTask::getStartedAt, now)
            .set(GitlabSyncTask::getHeartbeatAt, now)
            .set(GitlabSyncTask::getUpdatedAt, now));
    GitlabSyncTask claimedTask = updated > 0 ? taskMapper.selectById(taskId) : null;
    if (claimedTask != null) {
      try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openTask(claimedTask, configService.getConfigById(claimedTask.getConfigId()));
           GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Start")) {
        log.info("Task claimed for execution, lockOwner={}", lockOwner);
      }
    }
    return claimedTask;
  }

  public void heartbeat(Long taskId) {
    LocalDateTime now = LocalDateTime.now();
    taskMapper.update(
        null,
        new LambdaUpdateWrapper<GitlabSyncTask>()
            .eq(GitlabSyncTask::getId, taskId)
            .in(GitlabSyncTask::getStatus, SyncStatus.RUNNING, SyncStatus.CANCELLING)
            .set(GitlabSyncTask::getHeartbeatAt, now)
            .set(GitlabSyncTask::getUpdatedAt, now));
  }

  public boolean isCancelRequested(Long taskId) {
    GitlabSyncTask task = taskMapper.selectById(taskId);
    return task != null && task.isCancelRequested();
  }

  public GitlabSyncTask requestCancelLatest(Long configId) {
    GitlabSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getConfigId, configId)
        .in(GitlabSyncTask::getStatus, SyncStatus.PENDING, SyncStatus.QUEUED, SyncStatus.RUNNING, SyncStatus.CANCELLING)
        .orderByDesc(GitlabSyncTask::getCreatedAt)
        .last("limit 1"));
    if (task == null) {
      return null;
    }
    SyncStatus nextStatus = (task.getStatus() == SyncStatus.PENDING || task.getStatus() == SyncStatus.QUEUED)
        ? SyncStatus.CANCELLED
        : SyncStatus.CANCELLING;
    LocalDateTime now = LocalDateTime.now();
    taskMapper.update(
        null,
        new LambdaUpdateWrapper<GitlabSyncTask>()
            .eq(GitlabSyncTask::getId, task.getId())
            .set(GitlabSyncTask::isCancelRequested, true)
            .set(GitlabSyncTask::getStatus, nextStatus)
            .set(GitlabSyncTask::getFinishedAt, nextStatus == SyncStatus.CANCELLED ? now : null)
            .set(GitlabSyncTask::getFinishedReason, "已收到用户中止请求")
            .set(GitlabSyncTask::getUpdatedAt, now));
    GitlabSyncTask updatedTask = taskMapper.selectById(task.getId());
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openTask(updatedTask, configService.getConfigById(updatedTask.getConfigId()));
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Cancel_Request")) {
      log.warn("Task cancellation requested, nextStatus={}", nextStatus);
    }
    return updatedTask;
  }

  public void finish(Long taskId, SyncStatus status, String reason, LocalDateTime cooldownUntil) {
    LocalDateTime now = LocalDateTime.now();
    taskMapper.update(
        null,
        new LambdaUpdateWrapper<GitlabSyncTask>()
            .eq(GitlabSyncTask::getId, taskId)
            .set(GitlabSyncTask::getStatus, status)
            .set(GitlabSyncTask::getFinishedReason, reason)
            .set(GitlabSyncTask::getFinishedAt, now)
            .set(GitlabSyncTask::getHeartbeatAt, now)
            .set(GitlabSyncTask::getCooldownUntil, cooldownUntil)
            .set(GitlabSyncTask::getUpdatedAt, now));
    GitlabSyncTask finishedTask = taskMapper.selectById(taskId);
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openTask(finishedTask, configService.getConfigById(finishedTask.getConfigId()));
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_End")) {
      log.info("Task finished, status={}, reason={}, cooldownUntil={}", status, reason, cooldownUntil);
    }
  }

  public GitlabSyncTask promoteNextQueued(String scopeKey) {
    GitlabSyncTask nextQueued = findLatestQueued(scopeKey);
    if (nextQueued == null) {
      return null;
    }
    LocalDateTime now = LocalDateTime.now();
    int updated = taskMapper.update(
        null,
        new LambdaUpdateWrapper<GitlabSyncTask>()
            .eq(GitlabSyncTask::getId, nextQueued.getId())
            .eq(GitlabSyncTask::getStatus, SyncStatus.QUEUED)
            .set(GitlabSyncTask::getStatus, SyncStatus.PENDING)
            .set(GitlabSyncTask::getUpdatedAt, now));
    GitlabSyncTask promotedTask = updated > 0 ? taskMapper.selectById(nextQueued.getId()) : null;
    if (promotedTask != null) {
      try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openTask(promotedTask, configService.getConfigById(promotedTask.getConfigId()));
           GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Submit")) {
        log.info("Queued task promoted to pending");
      }
    }
    return promotedTask;
  }

  public boolean hasActiveTask(Long configId) {
    return taskMapper.selectCount(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getConfigId, configId)
        .in(GitlabSyncTask::getStatus, ACTIVE_STATUSES)) > 0;
  }

  public boolean hasExecutingTask(Long configId) {
    return taskMapper.selectCount(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getConfigId, configId)
        .in(GitlabSyncTask::getStatus, SyncStatus.RUNNING, SyncStatus.CANCELLING)) > 0;
  }

  public void recoverTimedOutTasks() {
    String timeoutReason = "任务心跳超时";
    LocalDateTime threshold = LocalDateTime.now().minusSeconds(properties.getHeartbeatTimeoutSeconds());
    List<GitlabSyncTask> staleTasks = taskMapper.selectList(new LambdaQueryWrapper<GitlabSyncTask>()
        .in(GitlabSyncTask::getStatus, SyncStatus.RUNNING, SyncStatus.CANCELLING)
        .lt(GitlabSyncTask::getHeartbeatAt, threshold));
    for (GitlabSyncTask task : staleTasks) {
      try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openTask(task, configService.getConfigById(task.getConfigId()));
           GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Timeout_Recovered")) {
        log.error("Task heartbeat timed out, recovering task");
      }
      logService.finishRunningLogsForRecoveredTask(
          task.getConfigId(),
          task.getTaskType(),
          task.getStartedAt(),
          task.getHeartbeatAt(),
          timeoutReason);
      finish(
          task.getId(),
          SyncStatus.TIMEOUT,
          timeoutReason,
          LocalDateTime.now().plusMinutes(properties.getFailureBackoffMinutes()));
    }
  }

  public LocalDateTime resolveLatestActivityAt(Long configId) {
    GitlabSyncTask latestTask = taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getConfigId, configId)
        .orderByDesc(GitlabSyncTask::getUpdatedAt)
        .last("limit 1"));
    if (latestTask == null) {
      return null;
    }
    if (latestTask.getFinishedAt() != null) {
      return latestTask.getFinishedAt();
    }
    if (latestTask.getStartedAt() != null) {
      return latestTask.getStartedAt();
    }
    return latestTask.getCreatedAt();
  }

  public boolean isInCooldown(Long configId) {
    GitlabSyncTask latestTask = taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getConfigId, configId)
        .orderByDesc(GitlabSyncTask::getUpdatedAt)
        .last("limit 1"));
    return latestTask != null
        && latestTask.getCooldownUntil() != null
        && latestTask.getCooldownUntil().isAfter(LocalDateTime.now());
  }

  public String extractMessage(GitlabSyncTask task) {
    if (task == null) {
      return "";
    }
    if (task.getFinishedReason() != null && !task.getFinishedReason().isBlank()) {
      return task.getFinishedReason();
    }
    if (task.getPayloadJson() == null || task.getPayloadJson().isBlank()) {
      return defaultMessage(task);
    }
    Object value = jsonUtils.toMap(task.getPayloadJson()).get("message");
    return value == null ? defaultMessage(task) : String.valueOf(value);
  }

  public GitlabSyncTask findById(Long taskId) {
    return taskId == null ? null : taskMapper.selectById(taskId);
  }

  public int getFailureBackoffMinutes() {
    return properties.getFailureBackoffMinutes();
  }

  public String buildScopeKey(GitlabSyncConfig config) {
    String whitelistValue = switch (config.getWhitelistMode()) {
      case ALL -> "ALL";
      case RECOMMENDED -> "RECOMMENDED";
      case CUSTOM -> hashValue(String.join(",", config.getWhitelistTables() == null ? List.of() : config.getWhitelistTables()));
    };
    return "cfg-%s:mirror:%s:%s:%s:local".formatted(
        config.getId() == null ? "new" : config.getId(),
        config.getSourceMode() == null ? SourceMode.DOCKER : config.getSourceMode(),
        config.getWhitelistMode(),
        whitelistValue);
  }

  private GitlabSyncTask buildTask(
      GitlabSyncConfig config,
      SyncType taskType,
      SyncTriggerType triggerType,
      String scopeKey,
      String dedupeKey,
      SyncStatus status,
      Map<String, Object> payload) {
    LocalDateTime now = LocalDateTime.now();
    GitlabSyncTask task = new GitlabSyncTask();
    task.setRunId(UUID.randomUUID().toString().replace("-", ""));
    task.setConfigId(config.getId());
    task.setTaskType(taskType);
    task.setTriggerType(triggerType);
    task.setSourceMode(config.getSourceMode());
    task.setScopeKey(scopeKey);
    task.setDedupeKey(dedupeKey);
    task.setStatus(status);
    task.setPayloadJson(jsonUtils.toJson(payload));
    task.setRetryCount(0);
    task.setVersion(0);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    task.setHeartbeatAt(now);
    return task;
  }

  private GitlabSyncTask findRecentByDedupe(String dedupeKey) {
    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(properties.getDedupeWindowSeconds());
    return taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getDedupeKey, dedupeKey)
        .ge(GitlabSyncTask::getCreatedAt, cutoff)
        .notIn(GitlabSyncTask::getStatus, SyncStatus.FAILED, SyncStatus.CANCELLED, SyncStatus.TIMEOUT)
        .orderByDesc(GitlabSyncTask::getCreatedAt)
        .last("limit 1"));
  }

  private GitlabSyncTask findActiveByScope(String scopeKey) {
    return taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getScopeKey, scopeKey)
        .in(GitlabSyncTask::getStatus, ACTIVE_STATUSES)
        .orderByDesc(GitlabSyncTask::getCreatedAt)
        .last("limit 1"));
  }

  private GitlabSyncTask findLatestQueued(String scopeKey) {
    return taskMapper.selectOne(new LambdaQueryWrapper<GitlabSyncTask>()
        .eq(GitlabSyncTask::getScopeKey, scopeKey)
        .eq(GitlabSyncTask::getStatus, SyncStatus.QUEUED)
        .orderByAsc(GitlabSyncTask::getCreatedAt)
        .last("limit 1"));
  }

  private String buildDedupeKey(
      GitlabSyncConfig config,
      SyncType taskType,
      SyncTriggerType triggerType,
      Map<String, Object> payload) {
    return hashValue("%s|%s|%s|%s|%s".formatted(
        config.getId(),
        taskType,
        triggerType,
        buildScopeKey(config),
        jsonUtils.toJson(payload == null ? Map.of() : payload)));
  }

  private Map<String, Object> payloadWithMessage(String message, Map<String, Object> payload) {
    Map<String, Object> merged = new java.util.LinkedHashMap<>();
    merged.put("message", message);
    if (payload != null) {
      merged.putAll(payload);
    }
    return merged;
  }

  private String defaultMessage(GitlabSyncTask task) {
    return task.getTaskType() + " / " + task.getStatus();
  }

  private String hashValue(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build hash", e);
    }
  }
}
