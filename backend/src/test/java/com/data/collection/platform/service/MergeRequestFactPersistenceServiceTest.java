package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 合并请求事实持久化协作者的候选修订契约。
 *
 * <p>替换目标范围时先写 MR 候选修订，再只清理并重新绑定本候选修订上的提交关系，来源消失的根登记删除证明；
 * 已发布修订与其提交关系不被原地删除，因此旧版本仍可完整读取。
 */
class MergeRequestFactPersistenceServiceTest {
  private static final UUID PUBLICATION_ID = UUID.randomUUID();
  private static final UUID EPOCH_ID = UUID.randomUUID();

  private final MergeRequestFactMapper factMapper = mock(MergeRequestFactMapper.class);
  private final ReadPublicationRepository publicationRepository =
      mock(ReadPublicationRepository.class);
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final MergeRequestFactPersistenceService service =
      new MergeRequestFactPersistenceService(factMapper, publicationRepository, jdbcTemplate);

  @Test
  void test_replacement_orders_revision_write_before_commit_relation_rebind() {
    FactRevisionTarget target = target();
    MergeRequestFact fact = fact(201L);
    MergeRequestCommitFact commit = commit(201L, "abc123");

    service.replaceRootFacts(target, List.of(201L), List.of(fact), List.of(commit));

    InOrder ordered = Mockito.inOrder(factMapper, jdbcTemplate);
    ordered.verify(factMapper).batchUpsert(List.of(fact), EPOCH_ID, PUBLICATION_ID);
    ordered
        .verify(jdbcTemplate)
        .update(
            org.mockito.ArgumentMatchers.contains("delete from merge_request_commit_fact"),
            eq(PUBLICATION_ID),
            eq("GITLAB"),
            eq("default"),
            org.mockito.ArgumentMatchers.any());
    ordered.verify(jdbcTemplate)
        .batchUpdate(
            org.mockito.ArgumentMatchers.contains("merge_request_revision_id"),
            eq(List.of(commit)),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any());
    verify(publicationRepository).recordDeletionsForRoots(PublicationFactTable.MERGE_REQUEST,
        PUBLICATION_ID, "GITLAB", "default", List.of(201L), 7L);
  }

  @Test
  void test_vanished_merge_request_records_delete_proof_without_deleting_published_rows() {
    FactRevisionTarget target = target();

    service.replaceRootFacts(target, List.of(201L, 202L), List.of(fact(201L)), List.of());

    verify(publicationRepository)
        .recordDeletionsForRoots(
            PublicationFactTable.MERGE_REQUEST,
            PUBLICATION_ID,
            "GITLAB",
            "default",
            List.of(201L, 202L),
            7L);
  }

  @Test
  void test_commit_relation_cleanup_is_scoped_to_this_candidate_revision() {
    service.replaceRootFacts(target(), List.of(201L), List.of(fact(201L)), List.of());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .update(
            sql.capture(),
            eq(PUBLICATION_ID),
            eq("GITLAB"),
            eq("default"),
            org.mockito.ArgumentMatchers.any());
    assertThat(sql.getValue())
        .contains("delete from merge_request_commit_fact")
        .contains("fact.created_by_publication_id = ?")
        .doesNotContain("delete from merge_request_fact");
  }

  @Test
  void test_commit_upsert_writes_bounded_batches_bound_to_the_candidate_revision() {
    List<MergeRequestCommitFact> commits = new java.util.ArrayList<>();
    for (int index = 0; index < 201; index++) {
      commits.add(commit(201L, "sha" + index));
    }

    service.upsertCommitFacts(target(), commits);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .batchUpdate(
            sql.capture(),
            eq(commits),
            eq(200),
            org.mockito.ArgumentMatchers.any());
    assertThat(sql.getAllValues()).allSatisfy(statement ->
        assertThat(statement)
            .contains("insert into merge_request_commit_fact")
            .contains("fact.created_by_publication_id = ?")
            .contains("on conflict (merge_request_revision_id, commit_sha)"));
  }

  @Test
  void test_empty_target_range_is_rejected_because_replacement_needs_scope() {
    assertThatThrownBy(() -> service.replaceRootFacts(target(), List.of(), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空目标范围");
    verifyNoInteractions(factMapper);
  }

  private static FactRevisionTarget target() {
    return new FactRevisionTarget(
        PUBLICATION_ID,
        EPOCH_ID,
        PublicationFactTable.MERGE_REQUEST,
        "GITLAB",
        "default",
        7L);
  }

  private static MergeRequestFact fact(long mergeRequestId) {
    MergeRequestFact fact = mock(MergeRequestFact.class);
    when(fact.getMergeRequestId()).thenReturn(mergeRequestId);
    return fact;
  }

  private static MergeRequestCommitFact commit(long mergeRequestId, String commitSha) {
    return new MergeRequestCommitFact(
        "GITLAB", "default", 9L, mergeRequestId, 3L, commitSha, LocalDateTime.of(2026, 10, 1, 0, 0));
  }
}
