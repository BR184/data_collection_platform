package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactBuildResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class IntegrationTestFactPipelineTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationTestFactBuildService factBuildService;

  @BeforeEach
  void setUp() {
    createMinimalOdsTables();
    cleanTables();
  }

  @Test
  void fullBuildParsesLatestLegacyIntegrationNoteIntoFormalFact() {
    insertIntegrationIssue();

    FactBuildResponse response = factBuildService.rebuildFacts(true);

    assertThat(response.affectedRows()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForMap(
                """
                select source_instance, project_id, issue_iid, module_name, testing_phase,
                       function_name, executor, execute_case, pass_case, not_pass_case,
                       not_pass_case_now, problem_case, exception_count, pass_rate,
                       legal, parse_status
                  from integration_test_fact
                 where project_id = 9 and issue_iid = 101
                """))
        .containsEntry("source_instance", "default")
        .containsEntry("project_id", 9L)
        .containsEntry("issue_iid", 101L)
        .containsEntry("module_name", "草图")
        .containsEntry("testing_phase", "CC2026R3集成测试")
        .containsEntry("function_name", "拉伸")
        .containsEntry("executor", "张三")
        .containsEntry("execute_case", 10)
        .containsEntry("pass_case", 8)
        .containsEntry("not_pass_case", 2)
        .containsEntry("not_pass_case_now", 2)
        .containsEntry("problem_case", 1)
        .containsEntry("exception_count", 0)
        .containsEntry("legal", true)
        .containsEntry("parse_status", "PARSED");
  }

  @Test
  void targetedRefreshRemovesStaleFactWhenIntegrationMarkerDisappears() {
    insertIntegrationIssue();
    factBuildService.rebuildFacts(true);
    jdbcTemplate.update(
        "update ods_gitlab_notes set note = '## 普通评论', updated_at = current_timestamp "
            + "where noteable_id = 9001");

    FactBuildResponse response =
        factBuildService.rebuildFactsByRootIds("default", List.of(9001L));

    assertThat(response.affectedRows()).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from integration_test_fact where project_id = 9 and issue_iid = 101",
                Integer.class))
        .isZero();
  }

  @Test
  void fullBuildReconcilesFactsRemovedFromFormalOdsScope() {
    insertIntegrationIssue();
    factBuildService.rebuildFacts(true);
    jdbcTemplate.update("update ods_gitlab_issues set mirror_deleted = true where id = 9001");

    factBuildService.rebuildFacts(false);

    assertThat(jdbcTemplate.queryForObject("select count(*) from integration_test_fact", Integer.class))
        .isZero();
  }

  private void insertIntegrationIssue() {
    LocalDateTime now = LocalDateTime.of(2026, 7, 13, 9, 0);
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (9, 'CrownCAD', false)");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (501, '提交人', false)");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id, mirror_deleted
        ) values (9001, 101, 9, '集成测试样例', 501, ?, ?, null, 1, false)
        """,
        now.minusHours(1),
        now);
    insertLabel(1L, "模块：草图");
    insertLabel(2L, "CC2026R3集成测试");
    insertLabel(3L, "新功能");
    linkLabel(1L, 9001L);
    linkLabel(2L, 9001L);
    linkLabel(3L, 9001L);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(
          id, noteable_id, noteable_type, note, created_at, updated_at, mirror_deleted
        ) values
          (7001, 9001, 'Issue', '## 集成测试数据\n### 执行用例总数：99', ?, ?, false),
          (7002, 9001, 'Issue', ?, ?, ?, false)
        """,
        now.minusHours(2),
        now.minusHours(2),
        """
        ## 集成测试数据
        ### 功能：拉伸
        ### 执行人：张三
        ### 执行用例总数：10
        ### 初始未通过用例数：2
        ### 本次通过用例数：8
        ### 本次未通过用例数：2
        ### 本次问题用例数：1
        ### 用例外问题数：0
        """,
        now,
        now);
  }

  private void createMinimalOdsTables() {
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
  }

  private void cleanTables() {
    jdbcTemplate.update("delete from integration_test_fact");
    jdbcTemplate.update("delete from module_dictionary");
    jdbcTemplate.update("delete from issue_scope_members");
    jdbcTemplate.update("delete from issue_scope_groups");
    jdbcTemplate.update("delete from issue_scope_catalogs");
    jdbcTemplate.update("delete from ods_gitlab_label_links");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_notes");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    jdbcTemplate.update("delete from ods_gitlab_users");
    jdbcTemplate.update("delete from ods_gitlab_projects");
  }

  private void insertLabel(long id, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)", id, title);
  }

  private void linkLabel(long labelId, long issueId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(
          id, label_id, target_id, target_type, source_updated_at, updated_at, created_at, mirror_deleted
        ) values (?, ?, ?, 'Issue', current_timestamp, current_timestamp, current_timestamp, false)
        """,
        labelId,
        labelId,
        issueId);
  }
}
