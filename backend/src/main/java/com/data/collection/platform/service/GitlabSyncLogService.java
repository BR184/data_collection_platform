package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabSyncLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitlabSyncLogService {
  private final GitlabSyncLogMapper logMapper;
  private final GitlabSyncJobMapper jobMapper;
  private final JsonUtils jsonUtils;

  public GitlabSyncLogService(
      GitlabSyncLogMapper logMapper,
      GitlabSyncJobMapper jobMapper,
      JsonUtils jsonUtils) {
    this.logMapper = logMapper;
    this.jobMapper = jobMapper;
    this.jsonUtils = jsonUtils;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long start(Long configId, SyncType syncType, List<String> whitelistSnapshot, String message) {
    GitlabSyncLog log = new GitlabSyncLog();
    log.setConfigId(configId);
    log.setSyncType(syncType);
    log.setStatus(SyncStatus.RUNNING);
    log.setMessage(message);
    log.setWhitelistSnapshot(jsonUtils.toJson(whitelistSnapshot));
    log.setTableCount(0);
    log.setRecordCount(0);
    log.setStartedAt(LocalDateTime.now());
    logMapper.insert(log);
    return log.getId();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(long id, SyncStatus status, String message, int tableCount, int recordCount) {
    finishLog(id, status, message, tableCount, recordCount, LocalDateTime.now());
  }

  private void finishLog(
      long id,
      SyncStatus status,
      String message,
      int tableCount,
      int recordCount,
      LocalDateTime finishedAt) {
    LambdaUpdateWrapper<GitlabSyncLog> updateWrapper = new LambdaUpdateWrapper<GitlabSyncLog>()
        .eq(GitlabSyncLog::getId, id)
        .set(GitlabSyncLog::getStatus, status)
        .set(GitlabSyncLog::getMessage, message)
        .set(GitlabSyncLog::getTableCount, tableCount)
        .set(GitlabSyncLog::getRecordCount, recordCount)
        .set(GitlabSyncLog::getFinishedAt, finishedAt == null ? LocalDateTime.now() : finishedAt);
    logMapper.update(null, updateWrapper);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finishRunningLogsForCompletedJob(
      Long configId,
      SyncType syncType,
      LocalDateTime jobCreatedAt,
      LocalDateTime jobFinishedAt,
      SyncStatus status,
      String message) {
    if (configId == null || syncType == null || status == null || jobCreatedAt == null) {
      return;
    }
    LocalDateTime finishedAt = jobFinishedAt == null ? LocalDateTime.now() : jobFinishedAt;
    LambdaUpdateWrapper<GitlabSyncLog> updateWrapper = new LambdaUpdateWrapper<GitlabSyncLog>()
        .eq(GitlabSyncLog::getConfigId, configId)
        .eq(GitlabSyncLog::getSyncType, syncType)
        .eq(GitlabSyncLog::getStatus, SyncStatus.RUNNING)
        .ge(GitlabSyncLog::getStartedAt, jobCreatedAt.minusSeconds(10))
        .le(GitlabSyncLog::getStartedAt, finishedAt.plusSeconds(10))
        .set(GitlabSyncLog::getStatus, status)
        .set(GitlabSyncLog::getMessage, message)
        .set(GitlabSyncLog::getFinishedAt, finishedAt);
    logMapper.update(null, updateWrapper);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finishRunningLogsForRecoveredTask(
      Long configId,
      SyncType syncType,
      LocalDateTime taskStartedAt,
      LocalDateTime taskHeartbeatAt,
      String message) {
    if (configId == null || syncType == null || taskStartedAt == null) {
      return;
    }
    LocalDateTime latestLogStart = LocalDateTime.now();
    LambdaUpdateWrapper<GitlabSyncLog> updateWrapper = new LambdaUpdateWrapper<GitlabSyncLog>()
        .eq(GitlabSyncLog::getConfigId, configId)
        .eq(GitlabSyncLog::getSyncType, syncType)
        .eq(GitlabSyncLog::getStatus, SyncStatus.RUNNING)
        .ge(GitlabSyncLog::getStartedAt, taskStartedAt.minusSeconds(5))
        .le(GitlabSyncLog::getStartedAt, latestLogStart)
        .set(GitlabSyncLog::getStatus, SyncStatus.TIMEOUT)
        .set(GitlabSyncLog::getMessage, message)
        .set(GitlabSyncLog::getFinishedAt, LocalDateTime.now());
    logMapper.update(null, updateWrapper);
  }

  @Transactional
  public List<GitlabSyncLog> listRecent(Long configId, int limit) {
    reconcileCompletedRunningLogs(configId);
    return logMapper.selectList(new LambdaQueryWrapper<GitlabSyncLog>()
        .eq(GitlabSyncLog::getConfigId, configId)
        .orderByDesc(GitlabSyncLog::getStartedAt)
        .last("limit " + limit));
  }

  public GitlabSyncLog findLatest(Long configId) {
    return logMapper.selectOne(new LambdaQueryWrapper<GitlabSyncLog>()
        .eq(GitlabSyncLog::getConfigId, configId)
        .orderByDesc(GitlabSyncLog::getStartedAt)
        .last("limit 1"));
  }

  private void reconcileCompletedRunningLogs(Long configId) {
    if (configId == null) {
      return;
    }
    List<GitlabSyncLog> runningLogs = logMapper.selectList(new LambdaQueryWrapper<GitlabSyncLog>()
        .eq(GitlabSyncLog::getConfigId, configId)
        .eq(GitlabSyncLog::getStatus, SyncStatus.RUNNING)
        .orderByDesc(GitlabSyncLog::getStartedAt)
        .last("limit 50"));
    for (GitlabSyncLog log : runningLogs) {
      GitlabSyncJob completedJob = findCompletedJobForLog(log);
      if (completedJob == null) {
        continue;
      }
      finishLog(
          log.getId(),
          completedJob.getStatus(),
          buildCompletedJobMessage(completedJob, log.getSyncType()),
          log.getTableCount() == null ? 0 : log.getTableCount(),
          log.getRecordCount() == null ? 0 : log.getRecordCount(),
          completedJob.getFinishedAt());
    }
  }

  private GitlabSyncJob findCompletedJobForLog(GitlabSyncLog log) {
    if (log == null || log.getStartedAt() == null || log.getSyncType() == null) {
      return null;
    }
    List<GitlabSyncJobType> jobTypes = jobTypesForSyncType(log.getSyncType());
    if (jobTypes.isEmpty()) {
      return null;
    }
    List<GitlabSyncJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<GitlabSyncJob>()
        .eq(GitlabSyncJob::getConfigId, log.getConfigId())
        .in(GitlabSyncJob::getJobType, jobTypes)
        .in(GitlabSyncJob::getStatus, List.of(
            SyncStatus.SUCCESS,
            SyncStatus.PARTIAL_SUCCESS,
            SyncStatus.FAILED,
            SyncStatus.TIMEOUT,
            SyncStatus.CANCELLED))
        .le(GitlabSyncJob::getCreatedAt, log.getStartedAt().plusSeconds(10))
        .ge(GitlabSyncJob::getFinishedAt, log.getStartedAt().minusSeconds(10))
        .orderByDesc(GitlabSyncJob::getCreatedAt)
        .last("limit 1"));
    return jobs.isEmpty() ? null : jobs.get(0);
  }

  private List<GitlabSyncJobType> jobTypesForSyncType(SyncType syncType) {
    return switch (syncType) {
      case FULL -> List.of(GitlabSyncJobType.DAILY_VERIFY);
      case COMPENSATION -> List.of(GitlabSyncJobType.COMPENSATION_SCAN);
      case INCREMENTAL -> List.of(GitlabSyncJobType.MANUAL_REFRESH);
      case WEBHOOK -> List.of(GitlabSyncJobType.HOOK_WAKEUP);
      case PURGE -> List.of();
    };
  }

  private String buildCompletedJobMessage(GitlabSyncJob job, SyncType syncType) {
    if (job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
      return job.getErrorMessage();
    }
    String label = switch (syncType) {
      case FULL -> "Full table verification";
      case COMPENSATION -> "Compensation table sync";
      case INCREMENTAL -> "Incremental table refresh";
      case WEBHOOK -> "Webhook table refresh";
      case PURGE -> "Mirror purge";
    };
    return "%s completed with status %s".formatted(label, job.getStatus());
  }
}
