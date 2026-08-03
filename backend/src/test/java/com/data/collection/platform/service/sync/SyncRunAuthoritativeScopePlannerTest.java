package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.sync.SyncRunTableTask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SyncRunAuthoritativeScopePlannerTest {
  private SyncRunAuthoritativeScopeRepository repository;
  private SyncRunAuthoritativeScopePlanner planner;

  @BeforeEach
  void setUp() {
    repository = mock(SyncRunAuthoritativeScopeRepository.class);
    planner = new SyncRunAuthoritativeScopePlanner(repository);
  }

  @Test
  void test_incremental_issue_rows_enqueue_deduplicated_configured_scopes() {
    SyncRunTableTask task = producer("issues");
    when(repository.selectedSourceTables(77L)).thenReturn(java.util.Set.of("issue_assignees"));
    when(repository.enqueueScopes(
            eq(77L),
            eq("alpha"),
            eq(901L),
            eq("issue_assignees"),
            eq("issue-assignees"),
            anyList()))
        .thenAnswer(invocation -> ((List<?>) invocation.getArgument(5)).size());

    int inserted =
        planner.enqueueFromParentRows(
            task, List.of(Map.of("id", 101L), Map.of("id", 102L), Map.of("id", 101L)));

    assertThat(inserted).isEqualTo(2);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> scopes = ArgumentCaptor.forClass(List.class);
    verify(repository)
        .enqueueScopes(
            eq(77L),
            eq("alpha"),
            eq(901L),
            eq("issue_assignees"),
            eq("issue-assignees"),
            scopes.capture());
    assertThat(scopes.getValue())
        .containsExactly(Map.of("issue_id", 101L), Map.of("issue_id", 102L));
  }

  @Test
  void test_non_incremental_rows_do_not_recursively_enqueue_scopes() {
    SyncRunTableTask task = producer("notes");
    task.setRowStrategy("PRECISE");

    assertThat(
            planner.enqueueFromParentRows(
                task,
                List.of(
                    Map.of(
                        "id", 303L,
                        "noteable_id", 101L,
                        "noteable_type", "Issue"))))
        .isZero();

    verify(repository, never()).selectedSourceTables(77L);
  }

  private SyncRunTableTask producer(String sourceTable) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(901L);
    task.setRunId(77L);
    task.setSourceInstance("alpha");
    task.setSourceTable(sourceTable);
    task.setRowStrategy("INCREMENTAL");
    return task;
  }
}
