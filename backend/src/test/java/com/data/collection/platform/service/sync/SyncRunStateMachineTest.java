package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import org.junit.jupiter.api.Test;

class SyncRunStateMachineTest {
  @Test
  void shouldCentralizeActiveCompletedAndTerminalStates() {
    assertThat(SyncRunStateMachine.activeStatuses())
        .containsExactlyInAnyOrder(
            SyncRunStatus.SUBMITTED,
            SyncRunStatus.QUEUED,
            SyncRunStatus.RUNNING,
            SyncRunStatus.RETRYING,
            SyncRunStatus.CANCELLING);

    assertThat(SyncRunStateMachine.isActive(SyncRunStatus.RUNNING)).isTrue();
    assertThat(SyncRunStateMachine.isActive(SyncRunStatus.SUCCESS)).isFalse();
    assertThat(SyncRunStateMachine.isCompleted(SyncRunStatus.PARTIAL_SUCCESS)).isTrue();
    assertThat(SyncRunStateMachine.isTerminal(SyncRunStatus.MERGED)).isTrue();
  }

  @Test
  void shouldKeepExistingApiStatusMappingForMergedRuns() {
    assertThat(SyncRunStateMachine.toApiStatus(SyncRunStatus.MERGED)).isEqualTo(SyncStatus.PENDING);
  }
}
