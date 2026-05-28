package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncTableContinuationPlanner {
  private static final int DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE = 50000;

  private final SyncRunTableTaskMapper taskMapper;
  private final GitlabMirrorProperties mirrorProperties;

  public SyncTableContinuationPlanner(
      SyncRunTableTaskMapper taskMapper, GitlabMirrorProperties mirrorProperties) {
    this.taskMapper = taskMapper;
    this.mirrorProperties = mirrorProperties;
  }

  public void enqueueContinuationTask(
      SyncRunTableTask previousTask, LocalDateTime cursorUpdatedAt, String cursorPk, int batchSize) {
    int maxContinuationTasks = resolveMaxContinuationTasksPerTable();
    long existingTaskCount =
        taskMapper.selectCount(
            new LambdaQueryWrapper<SyncRunTableTask>()
                .eq(SyncRunTableTask::getRunId, previousTask.getRunId())
                .eq(SyncRunTableTask::getSourceTable, previousTask.getSourceTable()));
    if (existingTaskCount >= maxContinuationTasks) {
      log.error(
          "Exceeded max continuation tasks ({}) for table {}, runId={}, aborting further pagination",
          maxContinuationTasks,
          previousTask.getSourceTable(),
          previousTask.getRunId());
      throw new BizException(
          "\u8868 %2$s \u8d85\u8fc7\u8fde\u7eed\u5206\u9875\u4efb\u52a1\u4e0a\u9650\uff08%1$d\uff09"
              .formatted(maxContinuationTasks, previousTask.getSourceTable()));
    }
    taskMapper.insert(createContinuationTask(previousTask, cursorUpdatedAt, cursorPk, batchSize));
  }

  private SyncRunTableTask createContinuationTask(
      SyncRunTableTask previousTask, LocalDateTime cursorUpdatedAt, String cursorPk, int batchSize) {
    LocalDateTime now = LocalDateTime.now();
    SyncRunTableTask task = new SyncRunTableTask();
    task.setRunId(previousTask.getRunId());
    task.setConfigId(previousTask.getConfigId());
    task.setStateId(previousTask.getStateId());
    task.setSourceInstance(previousTask.getSourceInstance());
    task.setSourceTable(previousTask.getSourceTable());
    task.setMirrorTable(previousTask.getMirrorTable());
    task.setTaskType(previousTask.getTaskType());
    task.setStatus(SyncRunStatus.QUEUED);
    task.setRowStrategy(previousTask.getRowStrategy());
    task.setWatermarkAt(previousTask.getWatermarkAt());
    task.setCursorUpdatedAt(cursorUpdatedAt);
    task.setCursorPk(cursorPk);
    task.setLookupColumn(previousTask.getLookupColumn());
    task.setLookupValue(previousTask.getLookupValue());
    task.setBatchSize(batchSize);
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(previousTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private int resolveMaxContinuationTasksPerTable() {
    if (mirrorProperties == null) {
      return DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
    }
    int configured = mirrorProperties.getMaxContinuationTasksPerTable();
    return configured > 0 ? configured : DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
  }
}
