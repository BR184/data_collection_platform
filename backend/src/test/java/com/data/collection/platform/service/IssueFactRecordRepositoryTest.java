package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssueFactRecordRepositoryTest {

  @Mock private IssueFactQueryService issueFactQueryService;

  @Test
  void keywordSearchShouldFallbackToRawIssueFieldsWhenSearchIndexesAreEmpty() {
    IssueFactRecordRepository repository = new IssueFactRecordRepository(issueFactQueryService);
    when(issueFactQueryService.count(anyString(), anyList())).thenReturn(0L);

    repository.findPage(
        new IssueFactRecordPageQuery(
            IssueFactRecordPageQuery.Scope.ALL,
            request("22637", null, "cc"),
            null,
            null,
            null,
            null,
            java.util.List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            1,
             20,
             "updatedAt",
             "desc",
             null));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(issueFactQueryService).count(sqlCaptor.capture(), anyList());

    String sql = sqlCaptor.getValue();
    assertThat(sql).contains("search_text like ?");
    assertThat(sql).contains("cast(issue_iid as varchar) like ?");
    assertThat(sql).contains("lower(coalesce(title, '')) like ?");
    assertThat(sql).contains("lower(coalesce(project_name, '')) like ?");
    assertThat(sql).contains("lower(coalesce(module_names, '')) like ?");
    assertThat(sql).contains("lower(coalesce(milestone_title, '')) like ?");
    assertThat(sql).contains("lower(coalesce(author_name, '')) like ?");
    assertThat(sql).contains("lower(coalesce(assignee_name, '')) like ?");
  }

  @Test
  void issueNumberSearchTypeShouldUseIssueIidInsteadOfComprehensiveKeywordSearch() {
    IssueFactRecordRepository repository = new IssueFactRecordRepository(issueFactQueryService);
    when(issueFactQueryService.count(anyString(), anyList())).thenReturn(0L);

    repository.findPage(
        new IssueFactRecordPageQuery(
            IssueFactRecordPageQuery.Scope.ALL,
            request("22637", "issueIid", "cc"),
            null,
            null,
            null,
            null,
            java.util.List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            1,
             20,
             "updatedAt",
             "desc",
             null));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(issueFactQueryService).count(sqlCaptor.capture(), anyList());

    String sql = sqlCaptor.getValue();
    assertThat(sql).contains("cast(issue_iid as varchar) like ?");
    assertThat(sql).doesNotContain("search_text like ?");
    assertThat(sql).doesNotContain("lower(coalesce(title, '')) like ?");
  }

  @Test
  void customerSelectionShouldUseMembershipExistsWithoutDuplicatingIssueRows() {
    IssueFactRecordRepository repository = new IssueFactRecordRepository(issueFactQueryService);
    when(issueFactQueryService.count(anyString(), anyList())).thenReturn(0L);

    repository.findPage(
        new IssueFactRecordPageQuery(
            IssueFactRecordPageQuery.Scope.CUSTOMER,
            request(null, null, "default"),
            null,
            null,
            null,
            null,
            java.util.List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            1,
            20,
            "updatedAt",
            "desc",
            "郑州新世纪"));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<java.util.List<Object>> argsCaptor = ArgumentCaptor.forClass(java.util.List.class);
    verify(issueFactQueryService).count(sqlCaptor.capture(), argsCaptor.capture());

    assertThat(sqlCaptor.getValue())
        .contains("exists (select 1 from issue_fact_customer_members customer_member")
        .contains("customer_member.issue_id = issue_fact.issue_id");
    assertThat(argsCaptor.getValue()).contains("郑州新世纪");
  }

  private IssueFactRecordListRequest request(String keyword, String searchType, String sourceInstance) {
    return new IssueFactRecordListRequest(
        null,
        keyword,
        searchType,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        sourceInstance,
        1,
        20,
        "updatedAt",
        "desc");
  }
}
