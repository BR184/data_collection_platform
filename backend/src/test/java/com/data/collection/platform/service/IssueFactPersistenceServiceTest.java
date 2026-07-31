package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class IssueFactPersistenceServiceTest {

  @Test
  void test_target_replacement_deletes_customer_members_and_stale_fact_before_upsert() {
    IssueFactMapper factMapper = mock(IssueFactMapper.class);
    IssueFactCustomerMembershipRepository membershipRepository =
        mock(IssueFactCustomerMembershipRepository.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService service =
        new IssueFactPersistenceService(factMapper, membershipRepository, jdbcTemplate);
    IssueFact currentFact = mock(IssueFact.class);
    List<IssueFact> currentFacts = List.of(currentFact);

    service.replaceTargetFacts(
        "GITLAB",
        "default",
        List.of(new FactRefreshImpactScopeService.Target(9L, 101L)),
        currentFacts);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    InOrder ordered = inOrder(jdbcTemplate, factMapper, membershipRepository);
    ordered.verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(sqlCaptor.capture(), argsCaptor.capture());
    ordered.verify(factMapper).batchUpsert(currentFacts);
    ordered.verify(membershipRepository).replaceForFacts(currentFacts);
    assertThat(sqlCaptor.getAllValues().get(0))
        .contains("delete from issue_fact_customer_members")
        .contains("using issue_fact");
    assertThat(sqlCaptor.getAllValues().get(1)).contains("delete from issue_fact");
    assertThat(argsCaptor.getAllValues())
        .allSatisfy(args -> assertThat(args).containsExactly("GITLAB", "default", 9L, 101L));
  }

  @Test
  void test_empty_issue_source_still_deletes_target_projections() {
    IssueFactMapper factMapper = mock(IssueFactMapper.class);
    IssueFactCustomerMembershipRepository membershipRepository =
        mock(IssueFactCustomerMembershipRepository.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService service =
        new IssueFactPersistenceService(factMapper, membershipRepository, jdbcTemplate);

    service.replaceTargetFacts(
        "GITLAB",
        "default",
        List.of(new FactRefreshImpactScopeService.Target(9L, 101L)),
        List.of());

    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    org.mockito.Mockito.verifyNoInteractions(factMapper, membershipRepository);
  }

  @Test
  void test_empty_full_snapshot_clears_issue_facts_and_customer_members() {
    IssueFactMapper factMapper = mock(IssueFactMapper.class);
    IssueFactCustomerMembershipRepository membershipRepository =
        mock(IssueFactCustomerMembershipRepository.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService service =
        new IssueFactPersistenceService(factMapper, membershipRepository, jdbcTemplate);

    service.replaceAllFacts("GITLAB", "default", List.of());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(sqlCaptor.capture(), org.mockito.ArgumentMatchers.eq("GITLAB"),
            org.mockito.ArgumentMatchers.eq("default"));
    assertThat(sqlCaptor.getAllValues().get(0)).contains("issue_fact_customer_members");
    assertThat(sqlCaptor.getAllValues().get(1)).contains("issue_fact");
    org.mockito.Mockito.verifyNoInteractions(factMapper, membershipRepository);
  }
}
