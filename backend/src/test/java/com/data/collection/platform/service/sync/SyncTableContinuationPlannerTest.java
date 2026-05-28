package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncTableContinuationPlannerTest {
  private SyncRunTableTaskMapper taskMapper;
  private GitlabMirrorProperties mirrorProperties;
  private SyncTableContinuationPlanner planner;

  @BeforeEach
  void setUp() {
    taskMapper = org.mockito.Mockito.mock(SyncRunTableTaskMapper.class);
    mirrorProperties = new GitlabMirrorProperties();
    planner = new SyncTableContinuationPlanner(taskMapper, mirrorProperties);
  }

  @Test
  void shouldQueueContinuationTaskWithCursorAndCopiedTaskContext() {
    SyncRunTableTask previousTask = previousTask();
    LocalDateTime cursorUpdatedAt = LocalDateTime.of(2026, 5, 17, 10, 9);
    when(taskMapper.selectCount(any())).thenReturn(3L);

    planner.enqueueContinuationTask(previousTask, cursorUpdatedAt, "102", 200);

    verify(taskMapper)
        .insert(
            argThat(
                (SyncRunTableTask nextTask) ->
                    nextTask.getRunId().equals(77L)
                        && nextTask.getConfigId().equals(1L)
                        && nextTask.getStateId().equals(91L)
                        && "alpha".equals(nextTask.getSourceInstance())
                        && "issues".equals(nextTask.getSourceTable())
                        && "ods_gitlab_alpha_issues".equals(nextTask.getMirrorTable())
                        && "TABLE_REFRESH".equals(nextTask.getTaskType())
                        && SyncRunStatus.QUEUED.equals(nextTask.getStatus())
                        && "INCREMENTAL".equals(nextTask.getRowStrategy())
                        && previousTask.getWatermarkAt().equals(nextTask.getWatermarkAt())
                        && cursorUpdatedAt.equals(nextTask.getCursorUpdatedAt())
                        && "102".equals(nextTask.getCursorPk())
                        && "id".equals(nextTask.getLookupColumn())
                        && "101".equals(nextTask.getLookupValue())
                        && nextTask.getBatchSize().equals(200)
                        && nextTask.getRetryCount().equals(0)
                        && nextTask.getMaxRetryCount().equals(3)
                        && nextTask.getRowsScanned().equals(0L)
                        && nextTask.getRowsApplied().equals(0L)
                        && nextTask.getRunAfter() != null
                        && nextTask.getCreatedAt() != null
                        && nextTask.getUpdatedAt() != null));
  }

  @Test
  void shouldRejectContinuationWhenTableTaskCountReachesConfiguredLimit() {
    mirrorProperties.setMaxContinuationTasksPerTable(3);
    when(taskMapper.selectCount(any())).thenReturn(3L);

    assertThatThrownBy(
            () ->
                planner.enqueueContinuationTask(
                    previousTask(), LocalDateTime.of(2026, 5, 17, 10, 9), "102", 200))
        .isInstanceOf(BizException.class)
        .hasMessage("\u8868 issues \u8d85\u8fc7\u8fde\u7eed\u5206\u9875\u4efb\u52a1\u4e0a\u9650\uff083\uff09");

    verify(taskMapper, never()).insert(any(SyncRunTableTask.class));
  }

  @Test
  void shouldUseDefaultLimitWhenConfiguredLimitIsNotPositive() {
    mirrorProperties.setMaxContinuationTasksPerTable(0);
    when(taskMapper.selectCount(any())).thenReturn(49999L);

    planner.enqueueContinuationTask(previousTask(), null, "102", 200);

    verify(taskMapper).insert(any(SyncRunTableTask.class));
  }

  private SyncRunTableTask previousTask() {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setRunId(77L);
    task.setConfigId(1L);
    task.setStateId(91L);
    task.setSourceInstance("alpha");
    task.setSourceTable("issues");
    task.setMirrorTable("ods_gitlab_alpha_issues");
    task.setTaskType("TABLE_REFRESH");
    task.setStatus(SyncRunStatus.RUNNING);
    task.setRowStrategy("INCREMENTAL");
    task.setWatermarkAt(LocalDateTime.of(2026, 5, 17, 10, 0));
    task.setLookupColumn("id");
    task.setLookupValue("101");
    task.setBatchSize(500);
    task.setMaxRetryCount(3);
    return task;
  }
}
