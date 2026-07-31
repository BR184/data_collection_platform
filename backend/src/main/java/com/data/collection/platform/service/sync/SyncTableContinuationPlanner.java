package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunTableTaskStage;
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
    validateNextPage(previousTask);
    taskMapper.insert(createContinuationTask(previousTask, cursorUpdatedAt, cursorPk, batchSize));
  }

  public void enqueueReconciliationTask(
      SyncRunTableTask previousTask, String cursorPk, int batchSize) {
    validateNextPage(previousTask);
    SyncRunTableTask task = createContinuationTask(previousTask, null, cursorPk, batchSize);
    task.setTaskStage(SyncRunTableTaskStage.RECONCILE);
    task.setCursorUpdatedAt(null);
    taskMapper.insert(task);
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
    task.setTaskStage(previousTask.getTaskStage());
    task.setParentTaskId(previousTask.getId());
    task.setWatermarkAt(previousTask.getWatermarkAt());
    task.setCursorUpdatedAt(cursorUpdatedAt);
    task.setCursorPk(cursorPk);
    task.setScanUpperBoundAt(previousTask.getScanUpperBoundAt());
    task.setPageNumber(previousTask.getPageNumber() == null ? 2 : previousTask.getPageNumber() + 1);
    task.setLookupScopeJson(previousTask.getLookupScopeJson());
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

  private void validateNextPage(SyncRunTableTask previousTask) {
    int maxContinuationTasks = resolveMaxContinuationTasksPerTable();
    int currentPage = previousTask.getPageNumber() == null ? 1 : previousTask.getPageNumber();
    if (currentPage < maxContinuationTasks) {
      return;
    }
    log.error(
        "Exceeded max table pages ({}) for table {}, runId={}, aborting further pagination",
        maxContinuationTasks,
        previousTask.getSourceTable(),
        previousTask.getRunId());
    throw new BizException(
        "\u8868 %2$s \u8d85\u8fc7\u8fde\u7eed\u5206\u9875\u4efb\u52a1\u4e0a\u9650\uff08%1$d\uff09"
            .formatted(maxContinuationTasks, previousTask.getSourceTable()));
  }

  private int resolveMaxContinuationTasksPerTable() {
    if (mirrorProperties == null) {
      return DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
    }
    int configured = mirrorProperties.getMaxContinuationTasksPerTable();
    return configured > 0 ? configured : DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
  }
}
