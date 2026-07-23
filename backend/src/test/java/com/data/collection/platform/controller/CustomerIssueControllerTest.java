package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.service.CustomerIssueIllegalRecordQueryRequest;
import com.data.collection.platform.service.CustomerIssueIllegalRecordService;
import com.data.collection.platform.service.CustomerIssueRecordQueryRequest;
import com.data.collection.platform.service.CustomerIssueRecordService;
import com.data.collection.platform.service.IssueFactRecordListRequest;
import com.data.collection.platform.service.IssueFactRealtimeRefreshService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CustomerIssueControllerTest {

  @Mock private CustomerIssueIllegalRecordService customerIssueIllegalRecordService;
  @Mock private CustomerIssueRecordService customerIssueRecordService;
  @Mock private IssueFactRealtimeRefreshService realtimeRefreshService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new CustomerIssueController(
                    customerIssueIllegalRecordService,
                    customerIssueRecordService,
                    new CustomerIssueRequestAssembler(new IssueFactRecordListRequestAssembler()),
                    realtimeRefreshService))
            .build();
  }

  @Test
  void shouldReturnCustomerIssueRecords() throws Exception {
    when(customerIssueRecordService.listRecords(
            new CustomerIssueRecordQueryRequest(
                "delay",
                new IssueFactRecordListRequest(
                    325L,
                    "delay",
                    null,
                    "201",
                    "sample",
                    "CC_PRODUCT",
                    "Sketch",
                    null,
                    "S2",
                    "P1",
                    "opened",
                    "Open",
                    "Bug",
                    "R1",
                    "2026-04-01",
                    "2026-04-21",
                    "2026-04-10",
                    "2026-04-22",
                    null,
                    2,
                    10,
                    "updatedAt",
                    "desc"),
                "Design",
                "Alice",
                "Handler",
                "Bob",
                null,
                 null,
                 null,
                 null,
                 null)))
        .thenReturn(
            new CustomerIssueRecordListResponse(
                List.of(
                    new CustomerIssueRecordRowResponse(
                        9001L,
                        201,
                        "http://gitlab.example.com/-/issues/201",
                        325L,
                        "CC_PRODUCT",
                        "Delay sample",
                        "高晶电器、郑州新世纪",
                        "opened",
                        "S2",
                        "P1",
                        "Open",
                        "Bug",
                        "Design",
                        "R1",
                        "Alice",
                        "Handler",
                        "Bob",
                        "CC2026R3第一轮系统测试",
                        "Fixer",
                        "Sketch",
                        "Constraint",
                        true,
                        "Need extension",
                        "Customer delay",
                        true,
                        false,
                        false,
                        null,
                        LocalDateTime.of(2026, 4, 11, 10, 0),
                        240L,
                        LocalDateTime.of(2026, 4, 25, 0, 0),
                        "2026-04-25",
                        "release/2026R3",
                        LocalDateTime.of(2026, 4, 20, 16, 30),
                        null,
                        List.of("delay"))),
                1,
                2,
                10,
                "updatedAt",
                "desc"));

    mockMvc.perform(
            get("/api/customer-issues/records")
                .param("topic", "delay")
                .param("projectId", "325")
                .param("keyword", "delay")
                .param("issueIid", "201")
                .param("title", "sample")
                .param("projectName", "CC_PRODUCT")
                .param("moduleName", "Sketch")
                .param("reasonCategory", "Design")
                .param("authorName", "Alice")
                .param("handlerName", "Handler")
                .param("assigneeName", "Bob")
                .param("severityLevel", "S2")
                .param("priorityLevel", "P1")
                .param("issueState", "opened")
                .param("bugStatus", "Open")
                .param("category", "Bug")
                .param("milestoneTitle", "R1")
                .param("createdAtStart", "2026-04-01")
                .param("createdAtEnd", "2026-04-21")
                .param("updatedAtStart", "2026-04-10")
                .param("updatedAtEnd", "2026-04-22")
                .param("page", "2")
                .param("size", "10")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.records[0].issueIid").value(201))
        .andExpect(jsonPath("$.data.records[0].issueLink").value("http://gitlab.example.com/-/issues/201"))
        .andExpect(jsonPath("$.data.records[0].title").value("Delay sample"))
        .andExpect(jsonPath("$.data.records[0].testingPhase").value("CC2026R3第一轮系统测试"))
        .andExpect(jsonPath("$.data.records[0].handlerName").value("Handler"))
        .andExpect(jsonPath("$.data.records[0].assigneeName").value("Bob"))
        .andExpect(jsonPath("$.data.records[0].fixUser").value("Fixer"))
        .andExpect(jsonPath("$.data.records[0].labels[0]").value("delay"));
  }

  @Test
  void shouldExportCustomerIssueRecordsWorkbookWithCurrentFilters() throws Exception {
    when(customerIssueRecordService.exportRecordsWorkbook(
            new CustomerIssueRecordQueryRequest(
                "delay",
                new IssueFactRecordListRequest(
                    325L,
                    "delay",
                    null,
                    null,
                    null,
                    null,
                    "Sketch",
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
                "Design",
                null,
                null,
                null,
                null,
                null,
                null,
                 "{\"logic\":\"AND\",\"conditions\":[]}",
                 null)))
        .thenReturn(new byte[] {1, 2, 3});

    mockMvc.perform(
            get("/api/customer-issues/records/export")
                .param("topic", "delay")
                .param("projectId", "325")
                .param("keyword", "delay")
                .param("moduleName", "Sketch")
                .param("reasonCategory", "Design")
                .param("filterGroup", "{\"logic\":\"AND\",\"conditions\":[]}")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(header().string(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"download.xlsx\"; filename*=UTF-8''%E5%BB%B6%E6%9C%9F%E9%97%AE%E9%A2%98%E6%98%8E%E7%BB%86.xlsx"))
        .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(content().bytes(new byte[] {1, 2, 3}));
  }

  @Test
  void shouldReturnCustomerIssueRecordFilterOptionsAndRuleExplanation() throws Exception {
    when(customerIssueRecordService.getFilterOptions("delay", 325L))
        .thenReturn(
            new CustomerIssueRecordFilterOptionsResponse(
                List.of(new OptionItemResponse("CC_PRODUCT", "CC_PRODUCT")),
                List.of(new OptionItemResponse("Sketch", "Sketch")),
                List.of(new OptionItemResponse("Constraint", "Constraint")),
                List.of(new OptionItemResponse("高晶电器", "高晶电器")),
                List.of(new OptionItemResponse("Design", "Design")),
                List.of(new OptionItemResponse("S2", "S2")),
                List.of(new OptionItemResponse("P1", "P1")),
                List.of(new OptionItemResponse("opened", "opened")),
                List.of(new OptionItemResponse("Open", "Open")),
                List.of(new OptionItemResponse("Bug", "Bug")),
                List.of(new OptionItemResponse("Alice", "Alice")),
                List.of(),
                List.of(new OptionItemResponse("Bob", "Bob")),
                List.of(new OptionItemResponse("CC2026R3第一轮系统测试", "CC2026R3第一轮系统测试")),
                List.of(new OptionItemResponse("Carol", "Carol")),
                List.of(new OptionItemResponse("需求变更", "需求变更")),
                List.of(new OptionItemResponse("R1", "R1"))));
    when(customerIssueRecordService.getRuleExplanation("delay", 325L))
        .thenReturn(
            new StatisticBoardRuleExplanationResponse(
                "customer-issue-delay-records",
                true,
                "延期问题明细规则说明",
                "v1",
                "scope",
                "summary",
                List.of(),
                List.of(),
                null));

    mockMvc.perform(get("/api/customer-issues/records/filter-options").param("topic", "delay").param("projectId", "325"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.projectNames[0].value").value("CC_PRODUCT"))
        .andExpect(jsonPath("$.data.reasonCategories[0].value").value("Design"))
        .andExpect(jsonPath("$.data.authorNames[0].value").value("Alice"))
        .andExpect(jsonPath("$.data.assigneeNames[0].value").value("Bob"))
        .andExpect(jsonPath("$.data.testingPhases[0].value").value("CC2026R3第一轮系统测试"))
        .andExpect(jsonPath("$.data.fixUsers[0].value").value("Carol"))
        .andExpect(jsonPath("$.data.delayCauses[0].value").value("需求变更"));

    mockMvc.perform(get("/api/customer-issues/records/rule-explanation").param("topic", "delay").param("projectId", "325"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.boardKey").value("customer-issue-delay-records"))
        .andExpect(jsonPath("$.data.title").value("延期问题明细规则说明"));
  }

  @Test
  void shouldReturnCustomerIssueIllegalRecords() throws Exception {
    when(customerIssueIllegalRecordService.listRecords(
            new CustomerIssueIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    325L,
                    "illegal",
                    "301",
                    "sample",
                    "CC_PRODUCT",
                    "Sketch",
                    "S1",
                    "P0",
                    "opened",
                    "Open",
                    "Bug",
                    "R1",
                    "2026-04-01",
                    "2026-04-21",
                    "2026-04-10",
                    "2026-04-22",
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                "Module mismatch",
                null,
                null,
                null,
                null)))
        .thenReturn(
            new CustomerIssueIllegalRecordListResponse(
                List.of(
                    new CustomerIssueIllegalRecordRowResponse(
                        9002L,
                        301,
                        "http://gitlab.example.com/-/issues/301",
                        "default",
                        325L,
                        "CC_PRODUCT",
                        "Illegal sample",
                        "opened",
                        "R1",
                        "Module mismatch",
                        "S1",
                        "P0",
                        "Open",
                        "Bug",
                        "R1",
                        "Alice",
                        "Bob",
                        "Sketch",
                        "",
                        "",
                        "",
                        LocalDateTime.of(2026, 4, 11, 10, 0),
                        LocalDateTime.of(2026, 4, 20, 16, 30),
                        null,
                        List.of("illegal"))),
                1,
                1,
                20,
                "updatedAt",
                "desc"));

    mockMvc.perform(
            get("/api/customer-issues/illegal-records")
                .param("projectId", "325")
                .param("keyword", "illegal")
                .param("issueIid", "301")
                .param("title", "sample")
                .param("projectName", "CC_PRODUCT")
                .param("moduleName", "Sketch")
                .param("illegalReason", "Module mismatch")
                .param("severityLevel", "S1")
                .param("priorityLevel", "P0")
                .param("issueState", "opened")
                .param("bugStatus", "Open")
                .param("category", "Bug")
                .param("milestoneTitle", "R1")
                .param("createdAtStart", "2026-04-01")
                .param("createdAtEnd", "2026-04-21")
                .param("updatedAtStart", "2026-04-10")
                .param("updatedAtEnd", "2026-04-22")
                .param("page", "1")
                .param("size", "20")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.records[0].issueIid").value(301))
        .andExpect(jsonPath("$.data.records[0].issueLink").value("http://gitlab.example.com/-/issues/301"))
        .andExpect(jsonPath("$.data.records[0].illegalReason").value("Module mismatch"))
        .andExpect(jsonPath("$.data.records[0].labels[0]").value("illegal"));
  }

  @Test
  void shouldExportCustomerIssueIllegalRecordsWorkbookWithCurrentFilters() throws Exception {
    when(customerIssueIllegalRecordService.exportRecordsWorkbook(
            new CustomerIssueIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    325L,
                    "illegal",
                    null,
                    null,
                    null,
                    "Sketch",
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
                "Module mismatch",
                null,
                null,
                null,
                "{\"logic\":\"AND\",\"conditions\":[]}")))
        .thenReturn(new byte[] {4, 5, 6});

    mockMvc.perform(
            get("/api/customer-issues/illegal-records/export")
                .param("projectId", "325")
                .param("keyword", "illegal")
                .param("moduleName", "Sketch")
                .param("illegalReason", "Module mismatch")
                .param("filterGroup", "{\"logic\":\"AND\",\"conditions\":[]}")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(header().string(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"download.xlsx\"; filename*=UTF-8''%E5%A4%9A%E5%85%83%E8%AE%AE%E9%A2%98%E6%9F%A5%E8%AF%A2%E7%BB%93%E6%9E%9C.xlsx"))
        .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(content().bytes(new byte[] {4, 5, 6}));
  }

  @Test
  void shouldReturnCustomerIssueIllegalRecordFilterOptionsAndRuleExplanation() throws Exception {
    when(customerIssueIllegalRecordService.getFilterOptions(325L))
        .thenReturn(
            new CustomerIssueIllegalRecordFilterOptionsResponse(
                List.of(new OptionItemResponse("CC_PRODUCT", "CC_PRODUCT")),
                List.of(new OptionItemResponse("Sketch", "Sketch")),
                List.of(new OptionItemResponse("Constraint", "Constraint")),
                List.of(new OptionItemResponse("Module mismatch", "Module mismatch")),
                List.of(new OptionItemResponse("S1", "S1")),
                List.of(new OptionItemResponse("P0", "P0")),
                List.of(new OptionItemResponse("opened", "opened")),
                List.of(new OptionItemResponse("Open", "Open")),
                List.of(new OptionItemResponse("Bug", "Bug")),
                List.of(new OptionItemResponse("Alice", "Alice")),
                List.of(new OptionItemResponse("Bob", "Bob")),
                List.of(new OptionItemResponse("R1", "R1"))));
    when(customerIssueIllegalRecordService.getRuleExplanation(325L))
        .thenReturn(
            new StatisticBoardRuleExplanationResponse(
                "customer-issue-illegal-records",
                true,
                "客户问题缺陷非法数据规则说明",
                "v1",
                "scope",
                "summary",
                List.of(),
                List.of(),
                null));

    mockMvc.perform(get("/api/customer-issues/illegal-records/filter-options").param("projectId", "325"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.projectNames[0].value").value("CC_PRODUCT"))
        .andExpect(jsonPath("$.data.illegalReasons[0].value").value("Module mismatch"));

    mockMvc.perform(get("/api/customer-issues/illegal-records/rule-explanation").param("projectId", "325"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.boardKey").value("customer-issue-illegal-records"))
        .andExpect(jsonPath("$.data.title").value("客户问题缺陷非法数据规则说明"));
  }

  @Test
  void shouldReturnCustomerIssueRecordRealtimeStatusAndRefreshMessage() throws Exception {
    RealtimeWorkspaceStatusResponse status =
        new RealtimeWorkspaceStatusResponse(
            "customer-issue-delay-records", true, "READY", "refresh done", false, null, null, null);
    when(realtimeRefreshService.getStatus("customer-issue-delay-records", java.util.Map.of("topic", "delay"))).thenReturn(status);
    when(realtimeRefreshService.requestRefresh("customer-issue-delay-records")).thenReturn(status);

    mockMvc.perform(get("/api/customer-issues/records/status").param("topic", "delay"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workspaceKey").value("customer-issue-delay-records"));

    mockMvc.perform(post("/api/customer-issues/records/refresh").param("topic", "delay"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workspaceKey").value("customer-issue-delay-records"));
  }

  @Test
  void shouldReturnCustomerIssueIllegalRecordRealtimeStatusAndRefreshMessage() throws Exception {
    RealtimeWorkspaceStatusResponse status =
        new RealtimeWorkspaceStatusResponse(
            "customer-issue-illegal-records", true, "READY", "refresh done", false, null, null, null);
    when(realtimeRefreshService.getStatus("customer-issue-illegal-records", java.util.Map.of())).thenReturn(status);
    when(realtimeRefreshService.requestRefresh("customer-issue-illegal-records")).thenReturn(status);

    mockMvc.perform(get("/api/customer-issues/illegal-records/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workspaceKey").value("customer-issue-illegal-records"));

    mockMvc.perform(post("/api/customer-issues/illegal-records/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workspaceKey").value("customer-issue-illegal-records"));
  }
}
