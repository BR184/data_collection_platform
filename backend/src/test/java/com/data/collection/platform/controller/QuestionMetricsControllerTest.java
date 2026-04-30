package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordListResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordRowResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.IssueFactRecordListRequest;
import com.data.collection.platform.service.SystemTestIllegalRecordQueryRequest;
import com.data.collection.platform.service.SystemTestIllegalRecordService;
import com.data.collection.platform.service.SystemTestIssueSearchService;
import com.data.collection.platform.service.SystemTestIssueSearchQueryRequest;
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
class QuestionMetricsControllerTest {

  @Mock private SystemTestIssueSearchService systemTestIssueSearchService;
  @Mock private SystemTestIllegalRecordService systemTestIllegalRecordService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new QuestionMetricsController(
                    systemTestIssueSearchService,
                    systemTestIllegalRecordService,
                    new QuestionMetricsRequestAssembler(new IssueFactRecordListRequestAssembler())))
            .build();
  }

  @Test
  void shouldReturnIssueSearchList() throws Exception {
    when(systemTestIssueSearchService.listRecords(
            new SystemTestIssueSearchQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "草图",
                    "809",
                    "崩溃",
                    "Rocksdb",
                    "草图",
                    "LEVEL2",
                    null,
                    "opened",
                    "处理中",
                    "功能缺陷",
                    "CC2026R1",
                    "2026-04-01",
                    "2026-04-21",
                    "2026-04-10",
                    "2026-04-22",
                    2,
                    10,
                    "updatedAt",
                    "desc"),
                "CC2026R1",
                "alice",
                "bob")))
        .thenReturn(
            new SystemTestIssueSearchListResponse(
                List.of(
                    new SystemTestIssueSearchRowResponse(
                        9001L,
                        809,
                        "http://gitlab.example.com/-/issues/809",
                        1001L,
                        "Rocksdb",
                        "草图模块崩溃问题",
                        "opened",
                        "CC2026R1第一轮系统测试",
                        "LEVEL2",
                        "处理中",
                        "功能缺陷",
                        "CC2026R1",
                        "alice",
                        "bob",
                        "草图",
                        LocalDateTime.of(2026, 4, 11, 10, 0),
                        LocalDateTime.of(2026, 4, 20, 16, 30),
                        null,
                        List.of("CC2026R1第一轮系统测试", "系统测试"))),
                1,
                2,
                10,
                "updatedAt",
                "desc"));

    mockMvc.perform(
            get("/api/question-metrics/issues")
                .param("projectId", "1001")
                .param("keyword", "草图")
                .param("issueIid", "809")
                .param("title", "崩溃")
                .param("projectName", "Rocksdb")
                .param("moduleName", "草图")
                .param("testingPhase", "CC2026R1")
                .param("authorName", "alice")
                .param("assigneeName", "bob")
                .param("issueState", "opened")
                .param("severityLevel", "LEVEL2")
                .param("bugStatus", "处理中")
                .param("category", "功能缺陷")
                .param("milestoneTitle", "CC2026R1")
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
        .andExpect(jsonPath("$.data.records[0].issueIid").value(809))
        .andExpect(jsonPath("$.data.records[0].issueLink").value("http://gitlab.example.com/-/issues/809"))
        .andExpect(jsonPath("$.data.records[0].projectName").value("Rocksdb"))
        .andExpect(jsonPath("$.data.records[0].labels[0]").value("CC2026R1第一轮系统测试"));
  }

  @Test
  void shouldReturnIssueSearchFilterOptions() throws Exception {
    when(systemTestIssueSearchService.getFilterOptions(1001L))
        .thenReturn(
            new SystemTestIssueSearchFilterOptionsResponse(
                List.of(new OptionItemResponse("Rocksdb", "Rocksdb")),
                List.of(new OptionItemResponse("草图", "草图")),
                List.of(new OptionItemResponse("CC2026R1", "CC2026R1")),
                List.of(new OptionItemResponse("alice", "alice")),
                List.of(new OptionItemResponse("bob", "bob")),
                List.of(new OptionItemResponse("opened", "opened")),
                List.of(new OptionItemResponse("LEVEL2", "LEVEL2")),
                List.of(new OptionItemResponse("处理中", "处理中")),
                List.of(new OptionItemResponse("功能缺陷", "功能缺陷")),
                List.of(new OptionItemResponse("CC2026R1", "CC2026R1"))));

    mockMvc.perform(get("/api/question-metrics/issues/filter-options").param("projectId", "1001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.projectNames[0].value").value("Rocksdb"))
        .andExpect(jsonPath("$.data.testingPhases[0].value").value("CC2026R1"));
  }

  @Test
  void shouldExportIssueSearchRecordsCsvWithCurrentFilters() throws Exception {
    when(systemTestIssueSearchService.exportRecordsCsv(
            new SystemTestIssueSearchQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "sample",
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
                "CC2026R1",
                null,
                null)))
        .thenReturn("issue_iid,title\n809,Sample issue\n");

    mockMvc.perform(
            get("/api/question-metrics/issues/export")
                .param("projectId", "1001")
                .param("keyword", "sample")
                .param("moduleName", "Sketch")
                .param("testingPhase", "CC2026R1")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"system-test-issues.csv\""))
        .andExpect(content().contentType("text/csv;charset=UTF-8"))
        .andExpect(content().string("issue_iid,title\n809,Sample issue\n"));
  }

  @Test
  void shouldReturnSystemTestIllegalRecordList() throws Exception {
    when(systemTestIllegalRecordService.listRecords(
            new SystemTestIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "草图",
                    "809",
                    "崩溃",
                    "Rocksdb",
                    "草图",
                    "LEVEL2",
                    null,
                    "opened",
                    "处理中",
                    "功能缺陷",
                    "CC2026R1",
                    "2026-04-01",
                    "2026-04-21",
                    "2026-04-10",
                    "2026-04-22",
                    2,
                    10,
                    "updatedAt",
                    "desc"),
                "CC2026R1",
                "未设定模块",
                "alice",
                "bob",
                "{\"logic\":\"AND\",\"conditions\":[]}")))
        .thenReturn(
            new SystemTestIllegalRecordListResponse(
                List.of(
                    new SystemTestIllegalRecordRowResponse(
                        9001L,
                        809,
                        "http://gitlab.example.com/-/issues/809",
                        1001L,
                        "Rocksdb",
                        "草图模块崩溃问题",
                        "opened",
                        "CC2026R1第一轮系统测试",
                        "未设定模块",
                        "LEVEL2",
                        "处理中",
                        "功能缺陷",
                        "CC2026R1",
                        "alice",
                        "bob",
                        "草图",
                        LocalDateTime.of(2026, 4, 11, 10, 0),
                        LocalDateTime.of(2026, 4, 20, 16, 30),
                        null,
                        List.of("CC2026R1第一轮系统测试", "系统测试"))),
                1,
                2,
                10,
                "updatedAt",
                "desc"));

    mockMvc.perform(
            get("/api/question-metrics/illegal-records")
                .param("projectId", "1001")
                .param("keyword", "草图")
                .param("issueIid", "809")
                .param("title", "崩溃")
                .param("projectName", "Rocksdb")
                .param("moduleName", "草图")
                .param("testingPhase", "CC2026R1")
                .param("illegalReason", "未设定模块")
                .param("authorName", "alice")
                .param("assigneeName", "bob")
                .param("issueState", "opened")
                .param("severityLevel", "LEVEL2")
                .param("bugStatus", "处理中")
                .param("category", "功能缺陷")
                .param("milestoneTitle", "CC2026R1")
                .param("createdAtStart", "2026-04-01")
                .param("createdAtEnd", "2026-04-21")
                .param("updatedAtStart", "2026-04-10")
                .param("updatedAtEnd", "2026-04-22")
                .param("filterGroup", "{\"logic\":\"AND\",\"conditions\":[]}")
                .param("page", "2")
                .param("size", "10")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.records[0].issueIid").value(809))
        .andExpect(jsonPath("$.data.records[0].illegalReason").value("未设定模块"))
        .andExpect(jsonPath("$.data.records[0].testingPhase").value("CC2026R1第一轮系统测试"));
  }

  @Test
  void shouldReturnSystemTestIllegalRecordFilterOptions() throws Exception {
    when(systemTestIllegalRecordService.getFilterOptions(1001L))
        .thenReturn(
            new SystemTestIllegalRecordFilterOptionsResponse(
                List.of(new OptionItemResponse("Rocksdb", "Rocksdb")),
                List.of(new OptionItemResponse("草图", "草图")),
                List.of(new OptionItemResponse("CC2026R1", "CC2026R1")),
                List.of(new OptionItemResponse("未设定模块", "未设定模块")),
                List.of(new OptionItemResponse("alice", "alice")),
                List.of(new OptionItemResponse("bob", "bob")),
                List.of(new OptionItemResponse("opened", "opened")),
                List.of(new OptionItemResponse("LEVEL2", "LEVEL2")),
                List.of(new OptionItemResponse("处理中", "处理中")),
                List.of(new OptionItemResponse("功能缺陷", "功能缺陷")),
                List.of(new OptionItemResponse("CC2026R1", "CC2026R1"))));

    mockMvc.perform(get("/api/question-metrics/illegal-records/filter-options").param("projectId", "1001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.illegalReasons[0].value").value("未设定模块"));
  }

  @Test
  void shouldExportSystemTestIllegalRecordsCsvWithCurrentFilters() throws Exception {
    when(systemTestIllegalRecordService.exportRecordsCsv(
            new SystemTestIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "sample",
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
                "CC2026R1",
                "missing module",
                null,
                null,
                "{\"logic\":\"AND\",\"conditions\":[]}")))
        .thenReturn("issue_iid,illegal_reason\n809,missing module\n");

    mockMvc.perform(
            get("/api/question-metrics/illegal-records/export")
                .param("projectId", "1001")
                .param("keyword", "sample")
                .param("moduleName", "Sketch")
                .param("testingPhase", "CC2026R1")
                .param("illegalReason", "missing module")
                .param("filterGroup", "{\"logic\":\"AND\",\"conditions\":[]}")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"system-test-illegal-records.csv\""))
        .andExpect(content().contentType("text/csv;charset=UTF-8"))
        .andExpect(content().string("issue_iid,illegal_reason\n809,missing module\n"));
  }

  @Test
  void shouldReturnSystemTestIllegalRecordRuleExplanation() throws Exception {
    when(systemTestIllegalRecordService.getRuleExplanation(1001L))
        .thenReturn(
            new StatisticBoardRuleExplanationResponse(
                "system-test-illegal-records",
                true,
                "系统测试非法数据规则说明",
                "v1",
                "系统测试范围",
                "规则说明",
                List.of(),
                List.of(
                    new StatisticRuleMetricDefinition(
                        "missing-module", "未设定模块", "未设定模块定义", "公式", null)),
                null));

    mockMvc.perform(get("/api/question-metrics/illegal-records/rule-explanation").param("projectId", "1001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.title").value("系统测试非法数据规则说明"))
        .andExpect(jsonPath("$.data.metricDefinitions[0].label").value("未设定模块"));
  }
}
