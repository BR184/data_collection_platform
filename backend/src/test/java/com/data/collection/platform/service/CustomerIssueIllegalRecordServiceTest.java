package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
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
  @Mock private LabelGroupExpansionService labelGroupExpansionService;
  @Mock private FactBuildService factBuildService;
  @Mock private SystemTestPhaseScopeResolver phaseScopeResolver;

  @Test
  void shouldUseSqlPageForPlainIllegalListRequests() {
    CustomerIssueIllegalRecordService service =
        new CustomerIssueIllegalRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService,
            factBuildService,
            phaseScopeResolver);
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
            issueLinkService,
            labelGroupExpansionService,
            factBuildService,
            phaseScopeResolver);
    when(issueLinkService.issueUrl("default", 325L, 201))
        .thenReturn("http://gitlab.example.com/group/project/-/issues/201");
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
                null,
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

  @Test
  void shouldExpandLabelGroupFiltersThroughExistingFilterGroup() {
    CustomerIssueIllegalRecordService service =
        new CustomerIssueIllegalRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService,
            factBuildService,
            phaseScopeResolver);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(
            List.of(
                record(201, "illegal a", "draft", true, "missing module"),
                record(202, "illegal b", "sketch", true, "missing response")));
    when(labelGroupExpansionService.expand(
            9L, "STRING", "moduleName", "customer-issues-cc-product-issues", "default"))
        .thenReturn(new LabelGroupExpansionResponse(9L, "模块组", "STRING", List.of("draft"), List.of()));

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
                    "default",
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                null,
                null,
                """
                {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":9,"labelGroupName":"模块组"}]}
                """));

    assertThat(response.records()).extracting(CustomerIssueIllegalRecordRowResponse::issueIid).containsExactly(201);
    verify(issueFactRecordRepository, never()).findPage(any());
  }

  @Test
  void shouldUseMilestoneAsCustomerIssueTestingPhaseDisplayAndFilter() {
    CustomerIssueIllegalRecordService service =
        new CustomerIssueIllegalRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService,
            factBuildService,
            phaseScopeResolver);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(List.of(record(204, "illegal milestone", "draft", true, "missing module")));

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
                "R1",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().testingPhase()).isEqualTo("R1");
    verify(phaseScopeResolver, never()).matchesLegacyCrownCadPhase(any(), any());
  }

  @Test
  void shouldWriteExpandedLabelGroupSnapshotWhenExportingWorkbook() {
    CustomerIssueIllegalRecordService service =
        new CustomerIssueIllegalRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService,
            factBuildService,
            phaseScopeResolver);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(List.of(record(203, "illegal a", "draft", true, "missing module")));
    when(labelGroupExpansionService.expand(
            9L, "STRING", "moduleName", "customer-issues-cc-product-issues", "default"))
        .thenReturn(new LabelGroupExpansionResponse(9L, "模块组", "STRING", List.of("draft"), List.of()));

    byte[] workbook =
        service.exportRecordsWorkbook(
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
                    "default",
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                null,
                null,
                """
                {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":9,"labelGroupName":"模块组"}]}
                """));

    assertThat(workbook).isNotEmpty();
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
        "",
        List.of("customer"),
        false,
        "",
        "",
        false,
        false,
        illegal,
        illegalReason,
        illegal ? List.of(illegalReason) : List.of(),
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
