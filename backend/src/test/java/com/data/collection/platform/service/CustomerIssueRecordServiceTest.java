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
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
  void shouldForceLegacyCcProductProjectForPlainListRequests() {
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
                    9L,
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
                null,
                null,
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
                        && query.listRequest().projectId().equals(325L)
                        && !query.delayOnly()
                        && !query.illegalOnly()));
  }

  @Test
  void shouldApplyCcProductDisplayFieldFiltersToSqlPageQuery() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(new PageSlice<>(List.of(), 0, 1, 20));

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
                "装配",
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
                "王五",
                "张三",
             "新增需求",
             "李四",
             "需求变更",
             null,
             "高晶电器"));

    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    "装配".equals(query.listRequest().functionName())
                        && "王五".equals(query.handlerName())
                        && "张三".equals(query.assigneeName())
                        && "新增需求".equals(query.directTestingPhase())
                        && "李四".equals(query.fixUser())
                        && "需求变更".equals(query.delayCause())
                        && "高晶电器".equals(query.customerName())
                        && query.useFullTestingPhaseFilter()));
  }

  @Test
  void shouldKeepCustomerFieldsOutOfDelayTopicQueries() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(new PageSlice<>(List.of(), 0, 1, 20));

    service.listRecords(
        new CustomerIssueRecordQueryRequest(
            "delay",
            defaultListRequest(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "郑州新世纪"));

    verify(issueFactRecordRepository)
        .findPage(argThat(query -> query.customerName() == null && query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER));
  }

  @Test
  void shouldProjectCustomerAndTemplateFieldsWithoutCachingRetentionDuration() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService,
            labelGroupExpansionService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(new PageSlice<>(List.of(recordWithExportFields(1415, "客户议题", "", "", List.of())), 1, 1, 20));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "cc-product",
                defaultListRequest(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "郑州新世纪"));

    CustomerIssueRecordRowResponse row = response.records().getFirst();
    assertThat(row.customerNames()).isEqualTo("高晶电器、郑州新世纪");
    assertThat(row.retentionHours()).isPositive();
    assertThat(row.plannedResolutionText()).isEqualTo("2026年7月1日");
    assertThat(row.plannedMergeVersionBranch()).isEqualTo("release/2026R3");
    assertThat(row.handlerName()).isEqualTo("Handler");
    assertThat(row.assigneeName()).isEqualTo("Bob");
  }

  @Test
  void shouldDisplayAndFilterUnspecifiedTestingPhaseWithoutChangingTheFactValue() {
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
                List.of(recordWithExportFields(1416, "未设定阶段", "", "", List.of())), 1, 1, 20));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "cc-product",
                defaultListRequest(),
                null,
                null,
                null,
                null,
                CustomerIssueTestingPhaseSupport.UNSPECIFIED_DISPLAY_VALUE,
                null,
                null,
                null,
                null));

    assertThat(response.records().getFirst().testingPhase())
        .isEqualTo(CustomerIssueTestingPhaseSupport.UNSPECIFIED_DISPLAY_VALUE);
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    CustomerIssueTestingPhaseSupport.UNSPECIFIED_DISPLAY_VALUE.equals(
                        query.directTestingPhase())));
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
                null,
                null,
                null,
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
                null,
                null,
                null,
                null,
                 """
                 {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":8,"labelGroupName":"核心模块"}]}
                 """,
                 null));

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
                null,
                null,
                null,
                null,
                 """
                 {"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":10,"labelGroupName":"核心处理人"}]}
                 """,
                 null));

    assertThat(response.records()).extracting(CustomerIssueRecordRowResponse::issueIid).containsExactly(104);
  }

  @Test
  void shouldKeepRequestFiltersAndWritePageFieldsWhenExportingPagedRecords() throws Exception {
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
                List.of(
                    recordWithExportFields(
                        101,
                        "【约束】Export sample",
                        "CC2026R3第一轮系统测试",
                        "Fixer",
                        List.of(
                            "模块：草图",
                            "工具箱：工程图",
                            "测试阶段：CC2026R3第一轮系统测试",
                            "状态：已修复/完成",
                            "严重程度：二级缺陷",
                            "类别：缺陷",
                            "P1",
                            "技术卡点")),
                    recordWithExportFields(
                        102,
                        "[ASCII] Export sample",
                         "",
                         "",
                         List.of("模块：平台", "严重程度：三级缺陷", "类别：缺陷"),
                         "opened",
                         LocalDateTime.of(2026, 4, 25, 10, 0))),
                2,
                1,
                100));

    byte[] exported =
        service.exportRecordsWorkbook(
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
                    "",
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                null,
                null,
                "处理人筛选值",
                null,
                null,
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
                        && query.listRequest().size() == 100
                        && "处理人筛选值".equals(query.handlerName())));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
      var sheet = workbook.getSheet("CCProduct议题查询结果");
      assertThat(rowValues(sheet.getRow(0)))
          .containsExactly(
              "议题编号",
               "模块名",
               "功能名称",
               "议题标题",
               "客户",
               "议题提交人",
              "议题处理人",
              "议题指派人",
              "议题状态",
              "测试状态",
              "测试阶段",
              "严重程度",
              "缺陷优先级",
              "议题类别",
               "里程碑",
               "延期原因",
               "缺陷修复人",
               "计划解决时间",
               "计划合并版本分支",
               "提交时间",
               "缺陷滞留时长（小时）",
               "更新时间");
      List<String> firstRow = rowValues(sheet.getRow(1));
      assertThat(firstRow)
          .containsExactly(
              "#101",
              "草图、工程图",
              "约束",
              "【约束】Export sample",
              "高晶电器、郑州新世纪",
              "Alice",
              "Handler",
              "Bob",
              "未关闭",
              "已修复/完成",
              "CC2026R3第一轮系统测试",
              "二级缺陷",
              "P1",
              "缺陷",
              "CC2026 R3",
              "技术卡点",
              "Fixer",
               "2026年7月1日",
               "release/2026R3",
               "2026-04-21 10:00:00",
               firstRow.get(20),
               "2026-04-23 10:00:00");
      assertThat(firstRow.get(20)).matches("\\d+");
      List<String> secondRow = rowValues(sheet.getRow(2));
      assertThat(secondRow)
          .containsExactly(
              "#102",
              "草图、工程图",
              "约束",
              "[ASCII] Export sample",
              "高晶电器、郑州新世纪",
              "Alice",
              "Handler",
              "Bob",
              "已关闭",
              "已修复/完成",
              "未设定测试阶段",
              "二级缺陷",
              "P1",
              "缺陷",
              "CC2026 R3",
              "技术卡点",
              "",
               "2026年7月1日",
               "release/2026R3",
               "2026-04-21 10:00:00",
               secondRow.get(20),
               "2026-04-23 10:00:00");
      assertThat(secondRow.get(20)).matches("\\d+");
    }
  }

  @Test
  void shouldKeepDelayWorkbookLayoutIsolatedFromCcProductContract() throws Exception {
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
                List.of(
                    recordWithExportFields(
                        103,
                        "【延期】Export sample",
                        "CC2026R3第一轮系统测试",
                        "Fixer",
                        List.of("模块：草图", "工具箱：工程图"))),
                1,
                1,
                100));

    byte[] exported =
        service.exportRecordsWorkbook(
            new CustomerIssueRecordQueryRequest(
                "delay",
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
                null,
                null,
                null,
                null,
                null,
                null));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
      var row = workbook.getSheet("CCProduct议题查询结果").getRow(1);
      assertThat(row.getCell(2).getStringCellValue()).isEqualTo("草图、工程图");
      assertThat(row.getCell(7).getStringCellValue()).isEqualTo("未关闭");
      assertThat(row.getCell(9).getStringCellValue()).isEmpty();
      assertThat(row.getCell(16).getStringCellValue()).isEmpty();
    }
  }

  @Test
  void shouldWriteExpandedLabelGroupSnapshotWhenExportingWorkbook() {
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

    byte[] workbook =
        service.exportRecordsWorkbook(
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
                null,
                null,
                null,
                null,
                 """
                 {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":8,"labelGroupName":"核心模块"}]}
                 """,
                 null));

    assertThat(workbook).isNotEmpty();
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
    when(issueFactRecordRepository.findCustomerIssueRecordFilterValues(
            true, false, false, true, "cc"))
        .thenReturn(
            new IssueFactRecordRepository.CustomerIssueFilterValues(
                 List.of(),
                 List.of(),
                 List.of(),
                 List.of(),
                 List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("Bob"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

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

  private IssueFactRecord recordWithExportFields(
      int issueIid, String title, String testingPhase, String fixUser, List<String> labels) {
    return recordWithExportFields(issueIid, title, testingPhase, fixUser, labels, "opened");
  }

  private IssueFactRecord recordWithExportFields(
      int issueIid,
      String title,
      String testingPhase,
      String fixUser,
      List<String> labels,
      String issueState) {
    return recordWithExportFields(issueIid, title, testingPhase, fixUser, labels, issueState, null);
  }

  private IssueFactRecord recordWithExportFields(
      int issueIid,
      String title,
      String testingPhase,
      String fixUser,
      List<String> labels,
      String issueState,
      LocalDateTime closedAt) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        325L,
        "default",
        "CC_PRODUCT",
        9000L + issueIid,
        issueIid,
        title,
        issueState,
        testingPhase,
        "",
        "LEVEL2",
        "P1",
        "已修复/完成",
        "缺陷",
        "",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "CC2026 R3",
        "Alice",
        "Handler",
        "Bob",
        fixUser,
        List.of("草图", "工程图"),
        "约束",
        labels,
        false,
        "",
        "技术卡点",
        false,
        false,
        false,
        "",
        List.of(),
        now.minusDays(3),
        now.minusDays(1),
        closedAt,
        List.of("高晶电器", "郑州新世纪"),
        LocalDateTime.of(2026, 7, 1, 0, 0),
        "2026年7月1日",
        "release/2026R3");
  }

  private IssueFactRecordListRequest defaultListRequest() {
    return new IssueFactRecordListRequest(
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
        null,
        null,
        null,
        1,
        20,
        "updatedAt",
        "desc");
  }

  private List<String> rowValues(Row row) {
    return IntStream.range(0, CcProductIssueWorkbookRow.HEADERS.size())
        .mapToObj(index -> row.getCell(index).getStringCellValue())
        .toList();
  }
}
