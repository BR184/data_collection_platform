package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatisticBoardControllerTest {
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String STAT_LINK_SOURCE_INSTANCE = "stat_link_test";
  private static final String TEST_GITLAB_WEB_BASE_URL = "http://gitlab.test.local:18080";

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
        .containsExactly("projectName", "testingPhase", "moduleName", "severityLevel", "priorityLevel");
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
    assertThat(response.definition().columnGroups()).extracting("key")
        .containsExactly("requirement-problem", "implementation-problem", "environment-problem", "summary");
    assertThat(response.definition().columnGroups())
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("requirement-problem");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("requirement_understanding", "new_requirement");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("implementation-problem");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("implementation_logic");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("environment-problem");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("environment_deployment", "algorithm_mechanism", "other_reason");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("summary");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("total");
        });
    assertThat(response.meta().columnCount()).isEqualTo(7);
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
    assertThat(response.metricDefinitions()).extracting("key")
        .containsExactly(
            "requirement_understanding",
            "new_requirement",
            "implementation_logic",
            "environment_deployment",
            "algorithm_mechanism",
            "other_reason",
            "total");
  }

  @Test
  void shouldLoadCustomerIssueDefectSummaryBoard() {
    StatisticBoardResponse response = controller.getBoard("customer-issue-defect-summary", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("customer-issue-defect-summary");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块名称");
    assertThat(response.definition().filters()).extracting("key")
        .containsExactly("projectName", "moduleName", "severityLevel", "priorityLevel");
    assertThat(response.definition().columnGroups()).extracting("key")
        .containsExactly("level1", "level2", "level3", "suggestion", "priority-summary", "new-issue", "legacy");
    assertThat(response.meta().columnCount()).isEqualTo(38);
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
    assertThat(response.definition().filters()).isEmpty();
    assertThat(response.definition().columnGroups()).extracting("key")
        .containsExactly("requirement-problem", "implementation-problem", "environment-problem", "summary");
    assertThat(response.meta().columnCount()).isEqualTo(7);
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
    assertThat(response.metricDefinitions()).extracting("key")
        .containsExactly(
            "requirement_understanding",
            "new_requirement",
            "implementation_logic",
            "environment_deployment",
            "algorithm_mechanism",
            "other_reason",
            "total");
  }

  @Test
  void shouldLoadCustomerIssueResponseEfficiencyBoard() {
    StatisticBoardResponse response =
        controller.getBoard("customer-issue-response-efficiency", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("customer-issue-response-efficiency");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块");
    assertThat(response.definition().filters()).extracting("key")
        .containsExactly("projectName", "moduleName", "severityLevel", "priorityLevel");
    assertThat(response.definition().columnGroups()).extracting("key")
        .containsExactly("response", "resolve");
    assertThat(response.definition().columnGroups())
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("response");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly(
                  "total",
                  "responded",
                  "unresponded",
                  "response_overdue",
                  "response_delayed",
                  "response_rate");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("resolve");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("resolve_delayed", "resolve_on_time", "resolve_delay_rate");
        });
    assertThat(response.meta().columnCount()).isEqualTo(9);
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
        .containsExactly("response_rate", "response_delayed", "resolve_delay_rate");
  }

  @Test
  void shouldLoadCustomerIssueByFunctionBoard() {
    StatisticBoardResponse response =
        controller.getBoard("customer-issue-by-function", Map.of()).getData();

    assertThat(response).isNotNull();
    assertThat(response.definition().boardKey()).isEqualTo("customer-issue-by-function");
    assertThat(response.definition().rowHeaderLabel()).isEqualTo("模块 / 功能");
    assertThat(response.definition().filters()).extracting("key")
        .containsExactly("projectName", "moduleName", "functionName", "milestoneTitle", "severityLevel");
    assertThat(response.definition().columnGroups()).extracting("key")
        .containsExactly("quantity", "severity");
    assertThat(response.definition().columnGroups())
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("quantity");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("total", "fixed", "open", "delay", "response_delayed", "function_ratio");
        })
        .anySatisfy(group -> {
          assertThat(group.key()).isEqualTo("severity");
          assertThat(group.leafColumns()).extracting("key")
              .containsExactly("level1", "level2", "level3", "suggestion");
        });
    assertThat(response.meta().columnCount()).isEqualTo(10);
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
        .containsExactly("total", "function_ratio", "severity");
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
                Map.of("sourceInstance", "stat-link-test"))
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
                Map.of("sourceInstance", "stat-link-test"))
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
    ResponseEntity<String> response = controller.exportBoard("mirror-table-overview", Map.of());
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).contains("统计对象");
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
        .isEqualTo(Map.of("label", String.valueOf(issueIid), "href", issueUrl));
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
  }
}
