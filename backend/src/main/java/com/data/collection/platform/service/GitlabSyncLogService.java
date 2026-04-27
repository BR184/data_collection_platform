package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.mapper.GitlabSyncLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GitlabSyncLogService {
  private final GitlabSyncLogMapper logMapper;
  private final JsonUtils jsonUtils;

  public GitlabSyncLogService(GitlabSyncLogMapper logMapper, JsonUtils jsonUtils) {
    this.logMapper = logMapper;
    this.jsonUtils = jsonUtils;
  }

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

  public void finish(long id, SyncStatus status, String message, int tableCount, int recordCount) {
    LambdaUpdateWrapper<GitlabSyncLog> updateWrapper = new LambdaUpdateWrapper<GitlabSyncLog>()
        .eq(GitlabSyncLog::getId, id)
        .set(GitlabSyncLog::getStatus, status)
        .set(GitlabSyncLog::getMessage, message)
        .set(GitlabSyncLog::getTableCount, tableCount)
        .set(GitlabSyncLog::getRecordCount, recordCount)
        .set(GitlabSyncLog::getFinishedAt, LocalDateTime.now());
    logMapper.update(null, updateWrapper);
  }

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

  public List<GitlabSyncLog> listRecent(Long configId, int limit) {
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
}
