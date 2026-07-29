package com.data.collection.platform.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class SyncRunLogContextTest {
  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void shouldCorrelateRunAndTaskAndRestoreNestedValues() {
    SyncRun run = new SyncRun();
    run.setId(41L);
    run.setRunId("sr_41");
    run.setConfigId(7L);
    run.setSourceInstance("default");
    run.setRunType(SyncRunType.FULL_SYNC);
    run.setExclusiveScope("source:7:default:mirror");
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(501L);
    task.setRunId(41L);
    task.setConfigId(7L);
    task.setSourceInstance("default");
    task.setSourceTable("issues");

    try (SyncRunLogContext.Scope runContext = SyncRunLogContext.openRun(run, null)) {
      assertThat(MDC.get("runId")).isEqualTo("sr_41");
      assertThat(MDC.get("runDbId")).isEqualTo("41");
      assertThat(MDC.get("configId")).isEqualTo("7");
      assertThat(MDC.get("runType")).isEqualTo("FULL_SYNC");
      try (SyncRunLogContext.Scope taskContext = SyncRunLogContext.openTask(task)) {
        assertThat(MDC.get("taskId")).isEqualTo("501");
        assertThat(MDC.get("sourceTable")).isEqualTo("issues");
      }
      assertThat(MDC.get("runId")).isEqualTo("sr_41");
      assertThat(MDC.get("taskId")).isNull();
    }

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
