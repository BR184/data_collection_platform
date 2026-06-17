package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
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
class CustomerIssueRecordServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;
  @Mock private GitlabResourceLinkService issueLinkService;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;

  @Test
  void shouldUseSqlPageForPlainListRequests() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(record(100, "CC_PRODUCT", List.of("draft"), false, false, "", "Alice", "Bob")),
                1,
                1,
                20));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "cc-product",
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
                null,
                null));

    assertThat(response.total()).isEqualTo(1);
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
                        && !query.delayOnly()
                        && !query.illegalOnly()));
  }

  @Test
  void shouldApplyTopicAndCommonFiltersThroughRequestObject() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(issueLinkService.issueUrl("default", 325L, 101))
        .thenReturn("http://gitlab.example.com/group/project/-/issues/101");
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    record(
                        101,
                        "CC_PRODUCT delayed issue",
                        List.of("draft"),
                        true,
                        false,
                        "design",
                        "Alice",
                        "Bob")),
                1,
                1,
                20));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "delay",
                new IssueFactRecordListRequest(
                    325L,
                    "delay",
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
                "design",
                null,
                null,
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(101);
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/group/project/-/issues/101");
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
                        && query.delayOnly()
                        && "design".equals(query.reasonCategory())
                        && query.listRequest().projectId().equals(325L)
                        && "delay".equals(query.listRequest().keyword())));
  }

  @Test
  void shouldExpandLabelGroupFiltersThroughExistingFilterGroup() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(
            List.of(
                record(101, "module a issue", List.of("草图"), false, false, "", "Alice", "Bob"),
                record(102, "module b issue", List.of("工程图"), false, false, "", "Alice", "Bob")));
    when(labelGroupExpansionService.expand(8L, "STRING", "moduleName", "customer-issues-cc-product-issues", "default"))
        .thenReturn(new LabelGroupExpansionResponse(8L, "核心模块", "STRING", List.of("草图"), List.of()));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "cc-product",
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
                null,
                """
                {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":8,"labelGroupName":"核心模块"}]}
                """));

    assertThat(response.records()).extracting(CustomerIssueRecordRowResponse::issueIid).containsExactly(101);
    verify(issueFactRecordRepository, never()).findPage(any());
  }

  @Test
  void shouldApplyAssigneeLabelGroupFiltersThroughExistingFilterGroup() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(
            List.of(
                record(104, "assigned to Bob", List.of("草图"), false, false, "", "Alice", "Bob"),
                record(105, "assigned to Carl", List.of("草图"), false, false, "", "Alice", "Carl")));
    when(labelGroupExpansionService.expand(
            10L, "STRING", "assigneeName", "customer-issues-cc-product-issues", "default"))
        .thenReturn(new LabelGroupExpansionResponse(10L, "核心处理人", "STRING", List.of("Bob"), List.of()));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "cc-product",
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
                null,
                """
                {"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":10,"labelGroupName":"核心处理人"}]}
                """));

    assertThat(response.records()).extracting(CustomerIssueRecordRowResponse::issueIid).containsExactly(104);
  }

  @Test
  void shouldKeepRequestFiltersWhenExportingPagedRecords() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(record(101, "CC_PRODUCT", List.of("draft"), false, false, "", "Alice", "Bob")),
                1,
                1,
                100));

    service.exportRecordsCsv(
        new CustomerIssueRecordQueryRequest(
            "cc-product",
            new IssueFactRecordListRequest(
                325L,
                "",
                null,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                1,
                20,
                "updatedAt",
                "desc"),
            null,
            null,
            null,
            null));

    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.listRequest().projectId().equals(325L)
                        && query.listRequest().page() == 1
                        && query.listRequest().size() == 100));
  }

  @Test
  void shouldWriteExpandedLabelGroupSnapshotWhenExportingCsv() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(List.of(record(103, "CC_PRODUCT", List.of("草图"), false, false, "", "Alice", "Bob")));
    when(labelGroupExpansionService.expand(8L, "STRING", "moduleName", "customer-issues-cc-product-issues", "default"))
        .thenReturn(new LabelGroupExpansionResponse(8L, "核心模块", "STRING", List.of("草图"), List.of()));

    String csv =
        service.exportRecordsCsv(
            new CustomerIssueRecordQueryRequest(
                "cc-product",
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
                null,
                """
                {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":8,"labelGroupName":"核心模块"}]}
                """));

    assertThat(csv)
        .startsWith("标签组筛选快照,")
        .contains("moduleName eq 核心模块（标签组：草图）");
  }

  @Test
  void shouldScopeFilterOptionsBySourceInstance() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);
    when(issueFactRecordRepository.findByProjectId(null))
        .thenReturn(
            List.of(
                recordWithSource("cc", 106, "cc source", List.of("草图"), "Alice", "Bob"),
                recordWithSource("default", 107, "default source", List.of("工程图"), "Alice", "Carl")));

    List<String> assigneeOptions =
        service.getFilterOptions("cc-product", null, "cc").assigneeNames().stream()
            .map(option -> option.value())
            .toList();

    assertThat(assigneeOptions).containsExactly("Bob");
  }

  private IssueFactRecord record(
      int issueIid,
      String title,
      List<String> moduleNames,
      boolean delayIssue,
      boolean illegal,
      String reasonCategory,
      String authorName,
      String assigneeName) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        325L,
        "CC_PRODUCT",
        9000L + issueIid,
        issueIid,
        title,
        "opened",
        "",
        "",
        "S2",
        "P1",
        "Open",
        "Bug",
        reasonCategory,
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "R1",
        authorName,
        assigneeName,
        moduleNames,
        "",
        List.of("customer"),
        delayIssue,
        delayIssue ? "delay requested" : "",
        "",
        false,
        false,
        illegal,
        illegal ? "illegal reason" : "",
        illegal ? List.of("illegal reason") : List.of(),
        now.minusDays(3),
        now.minusDays(1),
        null);
  }

  private IssueFactRecord recordWithSource(
      String sourceInstance,
      int issueIid,
      String title,
      List<String> moduleNames,
      String authorName,
      String assigneeName) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        325L,
        sourceInstance,
        "CC_PRODUCT",
        9000L + issueIid,
        issueIid,
        title,
        "opened",
        "",
        "",
        "S2",
        "P1",
        "Open",
        "Bug",
        "",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "R1",
        authorName,
        assigneeName,
        moduleNames,
        "",
        List.of("customer"),
        false,
        "",
        "",
        false,
        false,
        false,
        "",
        List.of(),
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
