package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactChangeIdentity;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.VersionedFactChangeTarget;
import com.data.collection.platform.service.FactChangeTargetService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncRunAuthoritativeScopeCommitServiceTest {
  private SyncRunAuthoritativeScopeRepository repository;
  private MirrorTableWriter mirrorTableWriter;
  private FactChangeTargetService factChangeTargetService;
  private SyncRunReconciliationCoordinator reconciliationCoordinator;
  private SyncRunAuthoritativeScopeCommitService commitService;

  @BeforeEach
  void setUp() {
    repository = mock(SyncRunAuthoritativeScopeRepository.class);
    mirrorTableWriter = mock(MirrorTableWriter.class);
    factChangeTargetService = mock(FactChangeTargetService.class);
    reconciliationCoordinator = mock(SyncRunReconciliationCoordinator.class);
    commitService =
        new SyncRunAuthoritativeScopeCommitService(
            repository,
            mirrorTableWriter,
            factChangeTargetService,
            reconciliationCoordinator);
  }

  @Test
  void test_batch_replaces_present_and_empty_scopes_before_marking_success() {
    List<SyncRunAuthoritativeScope> scopes =
        List.of(scope(11L, 901L, 101L), scope(12L, 902L, 102L));
    SourceTableSchema schema = schema();
    List<Map<String, Object>> firstRows =
        List.of(Map.of("issue_id", 101L, "user_id", 7L));
    MirrorRowChange removed =
        new MirrorRowChange(Map.of("issue_id", 102L, "user_id", 8L), Map.of());
    when(mirrorTableWriter.replaceAuthoritativeScope(
            schema, Map.of("issue_id", 101L), firstRows, 901L))
        .thenReturn(new MirrorMutationResult(1, List.of(), 1));
    when(mirrorTableWriter.replaceAuthoritativeScope(
            schema, Map.of("issue_id", 102L), List.of(), 902L))
        .thenReturn(new MirrorMutationResult(0, List.of(removed), 0));
    FactChangeIdentity identity =
        new FactChangeIdentity("alpha", FactType.ISSUE, 102L, 5L, 9L);
    when(factChangeTargetService.registerChanges(
            eq(77L), eq(902L), eq("alpha"), eq("issue_assignees"), any()))
        .thenReturn(List.of(new VersionedFactChangeTarget(77L, identity, 99L)));

    SyncRunAuthoritativeScopeCommitService.CommitResult result =
        commitService.commit(
            scopes,
            "owner-1",
            schema,
            Map.of(11L, firstRows, 12L, List.of()));

    assertThat(result)
        .isEqualTo(new SyncRunAuthoritativeScopeCommitService.CommitResult(2, 1, 1, 1));
    verify(repository).lockOwnedBatch(List.of(11L, 12L), "owner-1", 77L);
    verify(repository).completeOwnedBatch(List.of(11L, 12L), "owner-1", 77L);
    verify(reconciliationCoordinator).lockStageMutation(77L);
    verify(reconciliationCoordinator).planIfReady(77L);
  }

  @Test
  void test_missing_source_result_for_requested_scope_is_rejected_before_side_effects() {
    List<SyncRunAuthoritativeScope> scopes =
        List.of(scope(11L, 901L, 101L), scope(12L, 902L, 102L));

    assertThatThrownBy(
            () ->
                commitService.commit(
                    scopes,
                    "owner-1",
                    schema(),
                    Map.of(11L, List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("完整覆盖");

    verify(repository, never()).lockOwnedBatch(any(), any(), eq(77L));
    verify(mirrorTableWriter, never())
        .replaceAuthoritativeScope(any(), any(), any(), any());
  }

  private SyncRunAuthoritativeScope scope(long id, long taskId, long issueId) {
    return new SyncRunAuthoritativeScope(
        id,
        77L,
        "alpha",
        "issue_assignees",
        "issue-assignees",
        "{\"issue_id\":" + issueId + "}",
        Map.of("issue_id", issueId),
        taskId,
        "owner-1",
        LocalDateTime.now().plusMinutes(1),
        0,
        3);
  }

  private SourceTableSchema schema() {
    return new SourceTableSchema(
        "ods_gitlab_issue_assignees",
        List.of("issue_id", "user_id"),
        null,
        List.of(
            new SourceTableColumn("issue_id", "bigint", false, 1),
            new SourceTableColumn("user_id", "bigint", false, 2)));
  }
}
