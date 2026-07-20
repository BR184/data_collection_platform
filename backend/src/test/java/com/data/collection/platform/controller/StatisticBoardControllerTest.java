package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatisticBoardControllerTest {
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String STAT_LINK_SOURCE_INSTANCE = "stat_link_test";
  private static final String TEST_GITLAB_WEB_BASE_URL = "http://gitlab.test.local:18080";
  private static final List<String> DEFECT_CAUSE_COLUMN_GROUP_KEYS =
      List.of(
          "requirement-problem",
          "design-problem",
          "code-problem",
          "package-problem",
          "dependency-problem",
          "precision-problem");
  private static final List<String> DEFECT_CAUSE_METRIC_KEYS =
      List.of(
          "demand_misunderstand",
          "missing_requirement",
          "add_demand_2",
          "demand_change_not_sync",
          "design_forget",
          "design_scheme",
          "incomplete",
          "prompt_message",
          "standard_error",
          "function_forget",
          "logic_calculation_algorithm_error",
          "logic_flow_control_error",
          "logic_data_state_process_error",
          "logic_business_logic_error",
          "logic_integration_interface_error",
          "environment_config_issue",
          "compilation_package_deployment_issue",
          "other_thirdParty",
          "algorithm_not_support",
          "mechanism_not_support",
          "precondition_data_exception",
          "other_unIdentifyTask",
          "precision_constraint_exception",
          "precision_algorithm_exception");

  @Autowired
  private StatisticBoardController controller;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetStatisticLinkFixtures() {
    ensureStatisticLinkProjectTables();
    cleanStatisticLinkFixtures();
  }

  @AfterEach
  void cleanStatisticLinkFixtures() {
    jdbcTemplate.update("delete from issue_fact where source_instance = ?", STAT_LINK_SOURCE_INSTANCE);
    jdbcTemplate.update("delete from ods_gitlab_projects where id in (?, ?)", 325L, 901L);
    jdbcTemplate.update("delete from ods_gitlab_stat_link_test_projects where id in (?, ?)", 325L, 901L);
    jdbcTemplate.update("delete from gitlab_sync_configs where source_instance = ?", STAT_LINK_SOURCE_INSTANCE);
  }

  @Test
  void shouldLoadMirrorTableOverviewBoard() {
    StatisticBoardResponse response = controller.getBoard("mirror-table-overview", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("mirror-table-overview");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("统计对象");
    assertThat(response.definition().columnGroups()).isNotEmpty();
    assertThat(response.meta()).isNotNull();
    assertThat(response.meta().rowCount()).isEqualTo(response.rows().size());
    assertThat(response.definition().filters()).extracting("key").contains("tableName", "totalRecords", "lastSyncedAt");
  }

  @Test
  void shouldLoadSystemTestDefectSummaryBoard() {
    StatisticBoardResponse response = controller.getBoard("system-test-defect-summary", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("system-test-defect-summary");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块名称");
    assertThat(response.definition().columnGroups()).extracting("key")
        .containsExactly("level1", "level2", "level3", "suggestion", "priority-summary", "new-issue", "legacy");
    assertThat(response.definition().filters()).extracting("key")
        .containsExactly(
            "projectName",
            "testingPhase",
            "moduleName",
            "title",
            "severityLevel",
            "priorityLevel",
            "bugStatus",
            "delayCause",
            "authorName",
            "assigneeName",
            "state",
            "createdAt",
            "updatedAt",
            "labels");
    assertThat(response.definition().columnGroups())
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("level1");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("level1_back", "level1_hang", "level1_other", "level1_fixed", "level1_total", "level1_rate");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("priority-summary");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly(
                  "p1_count", "p1_fix_rate", "p1_close_rate",
                  "p2_count", "p2_fix_rate", "p2_close_rate",
                  "p3_count", "p3_fix_rate",
                  "module_total", "defect_ratio", "delay_defect_ratio", "solved_count", "fix_rate", "close_rate",
                  "open_count", "extension_count", "retest_failed_count");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("legacy");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("level1_legacy_rate", "level2_legacy_count", "level3_legacy_count", "level23_legacy_rate");
        });
    assertThat(response.rows()).allSatisfy(row -> assertThat(row.rowLabel()).isNotBlank());
    assertThat(response.meta().rowCount()).isEqualTo(response.rows().size());
    assertThat(response.meta().columnCount()).isEqualTo(38);
  }

  @Test
  void shouldLoadSystemTestDefectSummaryRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("system-test-defect-summary", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("system-test-defect-summary");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.scopeDescription()).isNotBlank();
    assertThat(response.flowSteps()).isNotEmpty();
    assertThat(response.metricDefinitions()).isNotEmpty();
  }

  @Test
  void shouldLoadSystemTestPhaseStatisticsBoard() {
    StatisticBoardResponse response = controller.getBoard("system-test-phase-statistics", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("system-test-phase-statistics");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("轮次");
    assertThat(response.definition().filters()).extracting("key").containsExactly("testingPhase");
    assertThat(response.definition().columnGroups()).singleElement().satisfies(group -> {
      assertThat(group.key()).isEqualTo("phase-summary");
      assertThat(group.leafColumns()).extracting("key")
          .containsExactly("level1", "level2", "level3", "suggestion", "total");
    });
    assertThat(response.meta().columnCount()).isEqualTo(5);
  }

  @Test
  void shouldLoadSystemTestPhaseStatisticsRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("system-test-phase-statistics", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("system-test-phase-statistics");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.flowSteps()).isNotEmpty();
    assertThat(response.metricDefinitions()).extracting("key")
        .containsExactly("level1", "level2", "level3", "suggestion", "total");
  }

  @Test
  void shouldLoadSystemTestDelayAnalysisBoard() {
    StatisticBoardResponse response = controller.getBoard("system-test-delay-analysis", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("system-test-delay-analysis");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("延期原因");
    assertThat(response.definition().filters()).extracting("key").containsExactly("testingPhase");
    assertThat(response.definition().columnGroups()).singleElement().satisfies(group -> {
      assertThat(group.key()).isEqualTo("delay-summary");
      assertThat(group.leafColumns()).extracting("key")
          .containsExactly("level1", "level2", "level3", "suggestion", "total");
    });
    assertThat(response.meta().columnCount()).isEqualTo(5);
  }

  @Test
  void shouldLoadSystemTestDelayAnalysisRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("system-test-delay-analysis", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("system-test-delay-analysis");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.flowSteps()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(response.metricDefinitions()).extracting("key")
        .containsExactly("level1", "level2", "level3", "suggestion", "total");
  }

  @Test
  void shouldLoadSystemTestDefectCauseBoard() {
    StatisticBoardResponse response = controller.getBoard("system-test-defect-cause", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("system-test-defect-cause");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块");
    assertThat(response.definition().filters()).extracting("key").containsExactly("testingPhase");
    assertThat(response.definition().columnGroups()).extracting("key").containsExactlyElementsOf(DEFECT_CAUSE_COLUMN_GROUP_KEYS);
    assertThat(response.definition().columnGroups())
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("requirement-problem");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("demand_misunderstand", "missing_requirement", "add_demand_2", "demand_change_not_sync");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("code-problem");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly(
                  "standard_error",
                  "function_forget",
                  "logic_calculation_algorithm_error",
                  "logic_flow_control_error",
                  "logic_data_state_process_error",
                  "logic_business_logic_error",
                  "logic_integration_interface_error");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("dependency-problem");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly(
                  "other_thirdParty",
                  "algorithm_not_support",
                  "mechanism_not_support",
                  "precondition_data_exception",
                  "other_unIdentifyTask");
        });
    assertThat(response.meta().columnCount()).isEqualTo(24);
  }

  @Test
  void shouldLoadSystemTestDefectCauseRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("system-test-defect-cause", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("system-test-defect-cause");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.flowSteps()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(response.metricDefinitions()).extracting("key").containsExactlyElementsOf(DEFECT_CAUSE_METRIC_KEYS);
  }

  @Test
  void shouldLoadCustomerIssueDefectSummaryBoard() {
    StatisticBoardResponse response = controller.getBoard("customer-issue-defect-summary", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("customer-issue-defect-summary");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块名");
    assertThat(response.definition().filters()).extracting("key")
        .containsExactly(
            "projectName",
            "testingPhase",
            "milestoneTitle",
            "moduleName",
            "issueIid",
            "title",
            "severityLevel",
            "priorityLevel",
            "bugStatus",
            "category",
            "issueState",
            "authorName",
            "assigneeName");
    assertThat(response.definition().columnGroups()).extracting("key")
        .containsExactly("level1", "level2", "level3", "suggestion", "priority-summary", "new-issue", "legacy");
    assertThat(response.meta().columnCount()).isEqualTo(38);
  }

  @Test
  void shouldExportCustomerIssueSummaryWithLegacyIssueWorkbookContract() throws Exception {
    ResponseEntity<byte[]> response =
        controller.exportBoardIssues("customer-issue-defect-summary", Map.of());

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .contains("filename*=UTF-8''")
        .contains("%E5%AE%A2%E6%88%B7%E9%97%AE%E9%A2%98");
    try (XSSFWorkbook workbook =
        new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
      assertThat(workbook.sheetIterator())
          .toIterable()
          .extracting(sheet -> sheet.getSheetName())
          .containsExactly("议题数据");
      var header = workbook.getSheet("议题数据").getRow(0);
      assertThat(
              IntStream.range(0, header.getLastCellNum())
                  .mapToObj(index -> header.getCell(index).getStringCellValue())
                  .toList())
          .containsExactly(
              "议题更新时间", "议题提交时间", "模块名", "议题编号", "议题标题", "议题提交人",
              "议题处理人", "议题状态", "测试状态", "测试阶段", "议题严重程度", "议题类别", "里程碑",
              "议题指派人", "优先级", "延期原因", "缺陷修复人", "功能名称", "修复状态",
              "一级缺陷原因", "二级缺陷原因", "具体原因", "修改方案", "由修改其他缺陷造成的",
              "修改该缺陷可能影响的功能", "是否对可能影响的功能进行了测试", "有无遗留问题或潜在的影响",
              "是否更新了关联关系表", "议题关闭时间");
    }
  }

  @Test
  void shouldLoadCustomerIssueDefectSummaryRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("customer-issue-defect-summary", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("customer-issue-defect-summary");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.flowSteps()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(response.metricDefinitions()).extracting("key")
        .containsExactly("level1", "priority-summary", "summary", "new-issue", "legacy");
  }

  @Test
  void shouldLoadCustomerIssueDefectCauseBoard() {
    StatisticBoardResponse response = controller.getBoard("customer-issue-defect-cause", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("customer-issue-defect-cause");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块");
    assertThat(response.definition().filters()).extracting("key").containsExactly("testingPhase", "milestoneTitle");
    assertThat(response.definition().columnGroups()).extracting("key").containsExactlyElementsOf(DEFECT_CAUSE_COLUMN_GROUP_KEYS);
    assertThat(response.meta().columnCount()).isEqualTo(24);
  }

  @Test
  void shouldLoadCustomerIssueDefectCauseRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("customer-issue-defect-cause", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("customer-issue-defect-cause");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.flowSteps()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(response.metricDefinitions()).extracting("key").containsExactlyElementsOf(DEFECT_CAUSE_METRIC_KEYS);
  }

  @Test
  void shouldLoadCustomerIssueResponseEfficiencyBoard() {
    StatisticBoardResponse response =
        controller.getBoard("customer-issue-response-efficiency", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("customer-issue-response-efficiency");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块");
    assertThat(response.definition().filters()).extracting("key")
        .containsExactly(
            "projectName",
            "testingPhase",
            "milestoneTitle",
            "moduleName",
            "severityLevel",
            "priorityLevel",
            "issueState",
            "bugStatus",
            "authorName",
            "assigneeName");
    assertThat(response.definition().columnGroups()).extracting("key").containsExactly("legacy-fields");
    assertThat(response.definition().columnGroups()).singleElement().satisfies(group -> {
      assertThat(group.key()).isEqualTo("legacy-fields");
      assertThat(group.leafColumns()).extracting("key")
          .containsExactly("milestone_title", "response_cycle_hours", "resolution_cycle_days");
    });
    assertThat(response.meta().columnCount()).isEqualTo(3);
  }

  @Test
  void shouldLoadCustomerIssueResponseEfficiencyRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("customer-issue-response-efficiency", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("customer-issue-response-efficiency");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.flowSteps()).hasSizeGreaterThanOrEqualTo(3);
    assertThat(response.metricDefinitions()).extracting("key")
        .containsExactly("response_cycle_hours", "resolution_cycle_days");
  }

  @Test
  void shouldLoadCustomerIssueByFunctionBoard() {
    StatisticBoardResponse response =
        controller.getBoard("customer-issue-by-function", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("customer-issue-by-function");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("序号");
    assertThat(response.definition().filters()).extracting("key")
        .containsExactly("projectName", "testingPhase", "moduleName", "functionName", "milestoneTitle", "severityLevel");
    assertThat(response.definition().columnGroups()).extracting("key").containsExactly("placeholder");
    assertThat(response.definition().columnGroups()).singleElement().satisfies(group -> {
      assertThat(group.key()).isEqualTo("placeholder");
      assertThat(group.leafColumns()).extracting("key")
          .containsExactly("placeholder_function", "placeholder_count");
    });
    assertThat(response.meta().columnCount()).isEqualTo(2);
  }

  @Test
  void shouldLoadCustomerIssueByFunctionRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("customer-issue-by-function", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("customer-issue-by-function");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.flowSteps()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(response.metricDefinitions()).extracting("key")
        .containsExactly("legacy-pivot");
  }

  @Test
  void shouldLoadMirrorTableOverviewRuleExplanation() {
    StatisticBoardRuleExplanationResponse response =
        controller.getRuleExplanation("mirror-table-overview", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.boardKey()).isEqualTo("mirror-table-overview");
    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isNotBlank();
    assertThat(response.scopeDescription()).isNotBlank();
    assertThat(response.flowSteps()).isNotEmpty();
    assertThat(response.metricDefinitions()).isNotEmpty();
  }

  @Test
  void shouldFilterBoardBySelectedTableName() {
    StatisticBoardResponse initial = controller.getBoard("mirror-table-overview", Map.of()).getData();
    var tableField =
        initial.definition().filters().stream().filter(field -> field.key().equals("tableName")).findFirst().orElseThrow();
    if (tableField.options().isEmpty()) {
      assertThat(initial.rows()).isEmpty();
      return;
    }
    String selectedTable = tableField.options().get(0).value();
    StatisticBoardResponse filtered =
        controller.getBoard("mirror-table-overview", Map.of("filterGroup", textFilter("tableName", "eq", selectedTable))).getData();

    assertThat(filtered.rows()).hasSize(1);
    assertThat(filtered.rows().get(0).rowKey()).isEqualTo(selectedTable);
    assertThat(filtered.appliedFilterGroup()).isNotNull();
    assertThat(filtered.appliedFilterGroup().conditions()).hasSize(1);
  }

  @Test
  void shouldFilterBoardByNumericCondition() {
    StatisticBoardResponse initial = controller.getBoard("mirror-table-overview", Map.of()).getData();
    if (initial.rows().isEmpty()) {
      assertThat(initial.meta().rowCount()).isZero();
      return;
    }

    StatisticBoardResponse filtered =
        controller.getBoard("mirror-table-overview", Map.of("filterGroup", numberFilter("totalRecords", "gt", 0))).getData();
    assertThat(filtered.rows()).allSatisfy(row -> assertThat(row.cells()).isNotEmpty());
  }

  @Test
  void shouldRejectUnsupportedBoardKey() {
    assertThatThrownBy(() -> controller.getBoard("missing-board", Map.of()))
        .isInstanceOf(com.data.collection.platform.common.exception.BizException.class);
  }

  @Test
  void shouldLoadBoardDetail() {
    StatisticBoardResponse response = controller.getBoard("mirror-table-overview", Map.of()).getData();
    if (response.rows().isEmpty()) {
      return;
    }
    String rowKey = response.rows().get(0).rowKey();
    StatisticDetailResponse detail =
        controller.getDetails("mirror-table-overview", rowKey, "totalRecords", 1, 10, null, null, Map.of()).getData();

    assertThat(detail).isNotNull();
    assertThat(detail.columns()).isNotEmpty();
  }

  @Test
  void shouldExposeGitlabLinksInIssueStatisticDetails() {
    seedStatisticLinkIssue(
        325L,
        11001L,
        11001,
        "CC_Product",
        "Module A",
        "Function A",
        "",
        "LEVEL1",
        "stat-links/cc-product");
    seedStatisticLinkIssue(
        901L,
        12001L,
        12001,
        "System Test Project",
        "Module S",
        "Function S",
        "系统测试",
        "LEVEL1",
        "stat-links/system-test-project");

    assertIssueDetailLink(
        controller.getDetails(
                "customer-issue-defect-summary",
                "Module A",
                "level1_total",
                1,
                10,
                null,
                null,
                Map.of("sourceInstance", "stat-link-test", "milestoneTitle", "Milestone"))
            .getData(),
        11001,
        325L,
        "CC_Product",
        "stat-links/cc-product");
    assertIssueDetailLink(
        controller.getDetails(
                "customer-issue-by-function",
                "Module A||Function A",
                "total",
                1,
                10,
                null,
                null,
                Map.of("sourceInstance", "stat-link-test", "milestoneTitle", "Milestone"))
            .getData(),
        11001,
        325L,
        "CC_Product",
        "stat-links/cc-product");
    assertIssueDetailLink(
        controller.getDetails(
                "system-test-defect-summary",
                "Module S",
                "level1_total",
                1,
                10,
                null,
                null,
                Map.of("sourceInstance", "stat-link-test"))
            .getData(),
        12001,
        901L,
        "System Test Project",
        "stat-links/system-test-project");
  }

  @Test
  void shouldExportBoardCsv() {
    ResponseEntity<?> response = controller.exportBoard("mirror-table-overview", Map.of());
    assertThat(response.getBody()).isNotNull();
    assertThat(new String((byte[]) response.getBody(), StandardCharsets.UTF_8)).contains("统计对象");
  }

  private String textFilter(String fieldKey, String operator, String value) {
    return "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"" + fieldKey + "\",\"operator\":\"" + operator + "\",\"value\":\"" + value + "\"}]}";
  }

  private String numberFilter(String fieldKey, String operator, int value) {
    return "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"" + fieldKey + "\",\"operator\":\"" + operator + "\",\"value\":\"" + value + "\"}]}";
  }

  private void seedStatisticLinkIssue(
      long projectId,
      long issueId,
      int issueIid,
      String projectName,
      String moduleName,
      String functionName,
      String testingPhase,
      String severityLevel,
      String projectPath) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_projects(id, name, path, mirror_deleted)
        values (?, ?, ?, false)
        on conflict (id) do update
          set name = excluded.name,
              path = excluded.path,
              mirror_deleted = false
        """,
        projectId,
        projectName,
        projectPath);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_stat_link_test_projects(id, name, path, mirror_deleted)
        values (?, ?, ?, false)
        on conflict (id) do update
          set name = excluded.name,
              path = excluded.path,
              mirror_deleted = false
        """,
        projectId,
        projectName,
        projectPath);
    jdbcTemplate.update(
        """
        insert into gitlab_sync_configs(name, source_instance, web_base_url, source_mode, db_name, db_username, db_password)
        values (?, ?, ?, 'DOCKER', 'gitlabhq_production', 'gitlab_ro', 'secret')
        on conflict (source_instance) do update
          set web_base_url = excluded.web_base_url
        """,
        STAT_LINK_SOURCE_INSTANCE,
        STAT_LINK_SOURCE_INSTANCE,
        TEST_GITLAB_WEB_BASE_URL);
    jdbcTemplate.update(
        """
        insert into issue_fact(
          source_instance, project_id, project_name, issue_id, issue_iid, title, issue_state,
          milestone_title, author_name, created_at_source, updated_at_source, module_name,
          primary_module_name, module_names, function_name, testing_phase, primary_phase_label,
          severity_level, priority_level, label_names, is_excluded, is_fixed, deleted
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, false, false)
        """,
        STAT_LINK_SOURCE_INSTANCE,
        projectId,
        projectName,
        issueId,
        issueIid,
        "Statistic link issue " + issueIid,
        "opened",
        "Milestone",
        "Tester",
        LocalDateTime.of(2026, 2, 1, 10, 0),
        LocalDateTime.of(2026, 2, 2, 10, 0),
        moduleName,
        moduleName,
        moduleName,
        functionName,
        testingPhase,
        testingPhase,
        severityLevel,
        "P1",
        "");
  }

  private void assertIssueDetailLink(
      StatisticDetailResponse detail, int issueIid, long projectId, String projectName, String projectPath) {
    assertThat(detail).isNotNull();
    assertThat(detail.records()).hasSize(1);
    Map<String, Object> record = detail.records().get(0);
    String issueUrl = TEST_GITLAB_WEB_BASE_URL + "/" + projectPath + "/-/issues/" + issueIid;
    assertThat(record).containsEntry("issueIid", issueIid);
    assertThat(record).containsEntry("issueUrl", issueUrl);
    assertThat(record).containsEntry("projectId", projectId);
    assertThat(record).containsEntry("projectName", projectName);
    assertThat(record.get("iid"))
        .isEqualTo(Map.of("label", "#" + issueIid, "href", issueUrl));
  }

  private void ensureStatisticLinkProjectTables() {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects (
          id bigint primary key,
          name varchar(255),
          path varchar(255),
          namespace_id bigint,
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_namespaces (
          id bigint primary key,
          path varchar(255),
          full_path varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_stat_link_test_projects (
          id bigint primary key,
          name varchar(255),
          path varchar(255),
          namespace_id bigint,
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_stat_link_test_namespaces (
          id bigint primary key,
          path varchar(255),
          full_path varchar(255),
          mirror_deleted boolean default false
        )
        """);
  }
}
