package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.service.FactChangeTargetService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncRunTablePageCommitServiceTest {
  @Mock private SyncRunTableTaskLeaseService leaseService;
  @Mock private SyncRunTableStateMapper stateMapper;
  @Mock private SyncTableContinuationPlanner continuationPlanner;
  @Mock private SyncRunAuthoritativeScopePlanner authoritativeScopePlanner;
  @Mock private MirrorTableWriter mirrorTableWriter;
  @Mock private FactChangeTargetService factChangeTargetService;
  @Mock private SyncRunReconciliationCoordinator reconciliationCoordinator;

  private SyncRunTablePageCommitService service;

  @BeforeEach
  void setUp() {
    service =
        new SyncRunTablePageCommitService(
            leaseService,
            stateMapper,
            continuationPlanner,
            authoritativeScopePlanner,
            mirrorTableWriter,
            factChangeTargetService,
            reconciliationCoordinator);
  }

  @Test
  void fullMonotonicScanStoresFinalPrimaryKeyCursor() {
    SyncRunTableTask task = fullScanTask();
    SyncRunTableState state = monotonicState("[\"390838\"]");
    when(mirrorTableWriter.writeBatch(any(), anyList(), eq(71L), eq(true)))
        .thenReturn(MirrorMutationResult.empty());
    when(leaseService.finishOwnedTask(
            eq(71L),
            eq("owner"),
            eq(1L),
            eq(0L),
            eq("SUCCESS"),
            isNull(),
            isNull(),
            eq("[\"387476\"]")))
        .thenReturn(true);

    service.commitScanPage(
        task,
        state,
        mock(SourceTableSchema.class),
        List.of(Map.of("id", 387476L)),
        null,
        "[\"387476\"]",
        500,
        false,
        true,
        true);

    assertThat(state.getLastCursorPk()).isEqualTo("[\"387476\"]");
  }

  @Test
  void emptyFullMonotonicScanClearsStalePrimaryKeyCursor() {
    SyncRunTableTask task = fullScanTask();
    SyncRunTableState state = monotonicState("[\"390838\"]");
    when(mirrorTableWriter.writeBatch(any(), anyList(), eq(71L), eq(true)))
        .thenReturn(MirrorMutationResult.empty());
    when(leaseService.finishOwnedTask(
            eq(71L),
            eq("owner"),
            eq(0L),
            eq(0L),
            eq("SUCCESS"),
            isNull(),
            isNull(),
            eq("[]")))
        .thenReturn(true);

    service.commitScanPage(
        task,
        state,
        mock(SourceTableSchema.class),
        List.of(),
        null,
        "[]",
        500,
        false,
        true,
        true);

    assertThat(state.getLastCursorPk()).isEqualTo("[]");
  }

  private SyncRunTableTask fullScanTask() {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(71L);
    task.setRunId(81L);
    task.setLeaseOwner("owner");
    task.setRowStrategy("FULL_RECONCILE");
    return task;
  }

  private SyncRunTableState monotonicState(String cursor) {
    SyncRunTableState state = new SyncRunTableState();
    state.setRowStrategy("MONOTONIC_PRIMARY_KEY");
    state.setLastCursorPk(cursor);
    state.setDirtyFlag(false);
    state.setRetryCount(2);
    return state;
  }
}
