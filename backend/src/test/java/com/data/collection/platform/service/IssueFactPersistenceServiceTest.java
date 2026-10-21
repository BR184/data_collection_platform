package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * 议题事实持久化协作者的候选修订契约。
 *
 * <p>P2 起替换目标范围不再删除已发布行：仍存在的事实写成候选修订，来源消失的根只登记删除证明，
 * 由发布事务关闭其旧修订区间。因此这里锁定“绝不删除已发布事实/关系”，只允许写候选修订。
 */
class IssueFactPersistenceServiceTest {
  private static final UUID PUBLICATION_ID = UUID.randomUUID();
  private static final UUID EPOCH_ID = UUID.randomUUID();

  private final IssueFactMapper factMapper = mock(IssueFactMapper.class);
  private final IssueFactCustomerMembershipRepository membershipRepository =
      mock(IssueFactCustomerMembershipRepository.class);
  private final ReadPublicationRepository publicationRepository =
      mock(ReadPublicationRepository.class);
  private final IssueFactPersistenceService service =
      new IssueFactPersistenceService(factMapper, membershipRepository, publicationRepository);

  @Test
  void test_replacement_writes_candidate_revision_then_its_customer_members() {
    FactRevisionTarget target = target();
    IssueFact fact = fact(101L);

    service.replaceRootFacts(target, List.of(101L), List.of(fact));

    InOrder ordered = Mockito.inOrder(factMapper, membershipRepository, publicationRepository);
    ordered.verify(factMapper).batchUpsert(List.of(fact), EPOCH_ID, PUBLICATION_ID);
    ordered.verify(membershipRepository).replaceForFacts(target, List.of(fact));
    ordered.verify(publicationRepository)
        .recordDeletionsForRoots(
            PublicationFactTable.ISSUE, PUBLICATION_ID, "GITLAB", "default", List.of(101L), 7L);
  }

  @Test
  void test_vanished_root_records_delete_proof_instead_of_deleting_published_rows() {
    FactRevisionTarget target = target();
    IssueFact surviving = fact(101L);

    service.replaceRootFacts(target, List.of(102L, 101L), List.of(surviving));

    // 考虑集合必须整体交给仓储按完整身份比较：本候选写出了 101，就不会被登记成删除。
    verify(publicationRepository)
        .recordDeletionsForRoots(
            PublicationFactTable.ISSUE,
            PUBLICATION_ID,
            "GITLAB",
            "default",
            List.of(101L, 102L),
            7L);
    verify(factMapper).batchUpsert(List.of(surviving), EPOCH_ID, PUBLICATION_ID);
  }

  @Test
  void test_empty_source_result_records_delete_proof_for_every_target_root() {
    FactRevisionTarget target = target();

    service.replaceRootFacts(target, List.of(101L, 102L), List.of());

    verifyNoInteractions(factMapper, membershipRepository);
    verify(publicationRepository)
        .recordDeletionsForRoots(
            PublicationFactTable.ISSUE,
            PUBLICATION_ID,
            "GITLAB",
            "default",
            List.of(101L, 102L),
            7L);
  }

  @Test
  void test_batching_keeps_each_candidate_write_group_bounded() {
    FactRevisionTarget target = target();
    List<IssueFact> facts = new java.util.ArrayList<>();
    for (long index = 1; index <= 201; index++) {
      facts.add(fact(index));
    }

    service.upsertIssueFacts(target, facts);

    verify(factMapper, Mockito.times(2)).batchUpsert(anyList(), eq(EPOCH_ID), eq(PUBLICATION_ID));
    verify(membershipRepository, Mockito.times(2)).replaceForFacts(eq(target), anyList());
  }

  @Test
  void test_empty_target_range_is_rejected_because_replacement_needs_scope() {
    assertThatThrownBy(() -> service.replaceRootFacts(target(), List.of(), List.of(fact(101L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空目标范围");
    verifyNoInteractions(factMapper, membershipRepository, publicationRepository);
  }

  private static FactRevisionTarget target() {
    return new FactRevisionTarget(
        PUBLICATION_ID, EPOCH_ID, PublicationFactTable.ISSUE, "GITLAB", "default", 7L);
  }

  private static IssueFact fact(long issueId) {
    IssueFact fact = mock(IssueFact.class);
    when(fact.getSourceSystem()).thenReturn("GITLAB");
    when(fact.getSourceInstance()).thenReturn("default");
    when(fact.getProjectId()).thenReturn(9L);
    when(fact.getIssueId()).thenReturn(issueId);
    return fact;
  }
}
