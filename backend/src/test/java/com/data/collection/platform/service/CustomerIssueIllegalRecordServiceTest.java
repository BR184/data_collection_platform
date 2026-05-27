package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerIssueIllegalRecordServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;
  @Mock private GitlabResourceLinkService issueLinkService;

  @Test
  void shouldUseSqlPageForPlainIllegalListRequests() {
    CustomerIssueIllegalRecordService service =
        new CustomerIssueIllegalRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(new PageSlice<>(List.of(record(200, "illegal", "draft", true, "missing module")), 1, 1, 20));

    CustomerIssueIllegalRecordListResponse response =
        service.listRecords(
            new CustomerIssueIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    325L,
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
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                null,
                null));

    assertThat(response.total()).isEqualTo(1);
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
                        && query.illegalOnly()));
  }

  @Test
  void shouldApplyIllegalFiltersThroughSharedPipeline() {
    CustomerIssueIllegalRecordService service =
        new CustomerIssueIllegalRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService);
    when(issueLinkService.issueUrl(325L, 201)).thenReturn("http://gitlab.example.com/group/project/-/issues/201");
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(record(201, "draft illegal", "draft", true, "missing module")),
                1,
                1,
                20));

    CustomerIssueIllegalRecordListResponse response =
        service.listRecords(
            new CustomerIssueIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    325L,
                    "illegal",
                    null,
                    null,
                    "CC_PRODUCT",
                    "draft",
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
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                "missing module",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(201);
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/group/project/-/issues/201");
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
                        && query.illegalOnly()
                        && "missing module".equals(query.illegalReason())
                        && "illegal".equals(query.listRequest().keyword())));
  }

  private IssueFactRecord record(
      int issueIid, String title, String moduleName, boolean illegal, String illegalReason) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        325L,
        "CC_PRODUCT",
        9100L + issueIid,
        issueIid,
        title,
        "opened",
        "",
        "",
        "S1",
        "P0",
        "Open",
        "Bug",
        "design",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "R1",
        "Alice",
        "Bob",
        List.of(moduleName),
        List.of("customer"),
        false,
        "",
        "",
        false,
        false,
        illegal,
        illegalReason,
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
