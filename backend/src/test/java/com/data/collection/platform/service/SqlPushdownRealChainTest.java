package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SqlPushdownRealChainTest {
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from merge_request_fact");
    jdbcTemplate.update("delete from issue_fact");
  }

  @Test
  void shouldKeepCustomerIssuePinyinKeywordBehaviorThroughHttpSqlPath() throws Exception {
    insertIssueFact(
        325L,
        73001L,
        301,
        "CC_PRODUCT",
        "客户问题张三",
        "张三",
        "王强",
        "草图",
        "",
        "",
        "R1",
        false,
        "",
        "设计问题",
        "客户问题",
        LocalDateTime.of(2026, 2, 1, 10, 0));
    insertIssueFact(
        325L,
        73002L,
        302,
        "CC_PRODUCT",
        "客户问题章伞",
        "章伞",
        "李四",
        "草图",
        "",
        "",
        "R1",
        false,
        "",
        "设计问题",
        "客户问题",
        LocalDateTime.of(2026, 2, 2, 10, 0));

    JsonNode zhangsan =
        getJson(
            "/api/customer-issues/records",
            "projectId=325&keyword=zhangsan&page=1&size=20&sortBy=issueIid&sortOrder=asc");
    JsonNode initials =
        getJson(
            "/api/customer-issues/records",
            "projectId=325&keyword=zs&page=1&size=20&sortBy=issueIid&sortOrder=asc");

    assertThat(zhangsan.at("/data/total").asLong()).isEqualTo(2);
    assertThat(zhangsan.at("/data/records/0/issueIid").asInt()).isEqualTo(301);
    assertThat(zhangsan.at("/data/records/1/issueIid").asInt()).isEqualTo(302);
    assertThat(initials.at("/data/total").asLong()).isEqualTo(2);
  }

  @Test
  void shouldKeepSystemTestPhaseAndAdvancedFilterBehaviorThroughHttpSqlPath() throws Exception {
    insertIssueFact(
        1001L,
        74001L,
        401,
        "Rocksdb",
        "系统测试模块缺失",
        "alice",
        "bob",
        "",
        "CC2026R1第一轮系统测试",
        "CC2026R1第一轮系统测试",
        "CC2026R1",
        true,
        "缺失模块",
        "",
        "系统测试",
        LocalDateTime.of(2026, 3, 1, 10, 0));
    String filterGroup =
        objectMapper.writeValueAsString(
            objectMapper.createObjectNode()
                .put("logic", "AND")
                .set(
                    "conditions",
                    objectMapper
                        .createArrayNode()
                        .add(
                            objectMapper
                                .createObjectNode()
                                .put("fieldKey", "moduleName")
                                .put("operator", "isEmpty"))));

    URI url =
        UriComponentsBuilder.fromPath("/api/question-metrics/illegal-records")
            .queryParam("projectId", 1001)
            .queryParam("testingPhase", "CC2026R1第一轮")
            .queryParam("illegalReason", "未设定模块")
            .queryParam("filterGroup", filterGroup)
            .queryParam("page", 1)
            .queryParam("size", 20)
            .queryParam("sortBy", "testingPhase")
            .queryParam("sortOrder", "asc")
            .build()
            .encode()
            .toUri();

    JsonNode response = getJson(url);

    assertThat(response.at("/data/total").asLong()).isEqualTo(1);
    assertThat(response.at("/data/records/0/issueIid").asInt()).isEqualTo(401);
    assertThat(response.at("/data/records/0/illegalReason").asText()).isEqualTo("未设定模块");
  }

  @Test
  void shouldKeepCodeReviewKeywordAndFilterGroupBehaviorThroughHttpSqlPath() throws Exception {
    insertMergeRequestFact(
        2001L,
        75001L,
        501,
        "Repo Project",
        "repo/demo",
        "张三提交登录模块",
        "张三",
        "",
        "master",
        "login",
        null,
        12,
        80);
    String filterGroup =
        objectMapper.writeValueAsString(
            objectMapper.createObjectNode()
                .put("logic", "AND")
                .set(
                    "conditions",
                    objectMapper
                        .createArrayNode()
                        .add(
                            objectMapper
                                .createObjectNode()
                                .put("fieldKey", "owner")
                                .put("operator", "contains")
                                .put("value", "zs"))));
    URI url =
        UriComponentsBuilder.fromPath("/api/code-review/illegal-records")
            .queryParam("keyword", "zhangsan")
            .queryParam("filterGroup", filterGroup)
            .queryParam("page", 1)
            .queryParam("size", 20)
            .queryParam("sortBy", "mergeRequestIid")
            .queryParam("sortOrder", "asc")
            .build()
            .encode()
            .toUri();

    JsonNode response = getJson(url);

    assertThat(response.at("/data/total").asLong()).isEqualTo(1);
    assertThat(response.at("/data/records/0/mergeRequestIid").asInt()).isEqualTo(501);
    assertThat(response.at("/data/records/0/illegalTypes").toString()).contains("缺少模块标签");
  }

  private JsonNode getJson(String path, String query) throws Exception {
    return getJson(path + "?" + query);
  }

  private JsonNode getJson(String url) throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.get("success").asBoolean()).isTrue();
    return body;
  }

  private JsonNode getJson(URI url) throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.get("success").asBoolean()).isTrue();
    return body;
  }

  private void insertIssueFact(
      Long projectId,
      Long issueId,
      Integer issueIid,
      String projectName,
      String title,
      String authorName,
      String assigneeName,
      String moduleNames,
      String testingPhase,
      String systemTestLabel,
      String milestoneTitle,
      boolean illegal,
      String illegalReason,
      String reasonCategory,
      String labelNames,
      LocalDateTime createdAt) {
    IssueFact fact = new IssueFact();
    fact.setProjectId(projectId);
    fact.setProjectName(projectName);
    fact.setIssueId(issueId);
    fact.setIssueIid(issueIid.longValue());
    fact.setTitle(title);
    fact.setMilestoneTitle(milestoneTitle);
    fact.setAuthorName(authorName);
    fact.setAssigneeName(assigneeName);
    fact.setModuleNames(moduleNames);
    fact.setTestingPhase(testingPhase);
    fact.setSystemTestLabel(systemTestLabel);
    fact.setReasonCategory(reasonCategory);
    fact.setIllegalReason(illegalReason);
    fact.setBugStatus("处理中");
    fact.setCategory("功能缺陷");
    fact.setLabelNames(labelNames);
    FactSearchIndexSupport.IssueSearchIndexes indexes = FactSearchIndexSupport.buildIssueIndexes(fact);
    jdbcTemplate.update(
        """
        insert into issue_fact(
          source_system, source_instance, project_id, project_name, issue_id, issue_iid, title,
          issue_state, milestone_title, author_name, assignee_name, created_at_source, updated_at_source,
          module_names, testing_phase, system_test_label, severity_level, priority_level, bug_status,
          category, reason_category, label_names, is_illegal, illegal_reason, deleted,
          primary_phase_label, phase_filter_value,
          search_text, search_compact, search_spell, search_initials,
          title_search_text, title_search_compact, title_search_spell, title_search_initials,
          module_search_text, module_search_compact, module_search_spell, module_search_initials,
          milestone_search_text, milestone_search_compact, milestone_search_spell, milestone_search_initials,
          author_search_text, author_search_compact, author_search_spell, author_search_initials,
          assignee_search_text, assignee_search_compact, assignee_search_spell, assignee_search_initials,
          phase_search_text, phase_search_compact, phase_search_spell, phase_search_initials
        ) values (
          'GITLAB', 'default', ?, ?, ?, ?, ?, 'opened', ?, ?, ?, ?, ?, ?, ?, ?, 'LEVEL2', 'P1', ?,
          ?, ?, ?, ?, ?, false, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?
        )
        """,
        projectId,
        projectName,
        issueId,
        issueIid,
        title,
        milestoneTitle,
        authorName,
        assigneeName,
        createdAt,
        createdAt.plusHours(1),
        moduleNames,
        testingPhase,
        systemTestLabel,
        "处理中",
        "功能缺陷",
        reasonCategory,
        labelNames,
        illegal,
        illegalReason,
        indexes.primaryPhaseLabel(),
        indexes.phaseFilterValue(),
        indexes.keyword().normalized(),
        indexes.keyword().compact(),
        indexes.keyword().spell(),
        indexes.keyword().initials(),
        indexes.title().normalized(),
        indexes.title().compact(),
        indexes.title().spell(),
        indexes.title().initials(),
        indexes.module().normalized(),
        indexes.module().compact(),
        indexes.module().spell(),
        indexes.module().initials(),
        indexes.milestone().normalized(),
        indexes.milestone().compact(),
        indexes.milestone().spell(),
        indexes.milestone().initials(),
        indexes.author().normalized(),
        indexes.author().compact(),
        indexes.author().spell(),
        indexes.author().initials(),
        indexes.assignee().normalized(),
        indexes.assignee().compact(),
        indexes.assignee().spell(),
        indexes.assignee().initials(),
        indexes.phase().normalized(),
        indexes.phase().compact(),
        indexes.phase().spell(),
        indexes.phase().initials());
  }

  private void insertMergeRequestFact(
      Long projectId,
      Long mergeRequestId,
      Integer mergeRequestIid,
      String projectName,
      String repositoryName,
      String title,
      String ownerName,
      String labelNames,
      String targetBranch,
      String moduleName,
      BigDecimal commentRate,
      Integer defectCount,
      Integer addedLines) {
    MergeRequestFact fact = new MergeRequestFact();
    fact.setTitle(title);
    fact.setOwnerName(ownerName);
    fact.setProjectName(projectName);
    fact.setRepositoryName(repositoryName);
    fact.setModuleName(moduleName);
    fact.setTargetBranch(targetBranch);
    fact.setMergeUserName(ownerName);
    FactSearchIndexSupport.MergeRequestSearchIndexes indexes =
        FactSearchIndexSupport.buildMergeRequestIndexes(fact);
    LocalDateTime mergedAt = LocalDateTime.of(2026, 4, 1, 10, 0);
    jdbcTemplate.update(
        """
        insert into merge_request_fact(
          source_system, source_instance, project_id, project_name, repository_name, merge_request_id,
          merge_request_iid, title, merge_request_state, target_branch, source_branch, author_name,
          merge_user_name, owner_name, module_name, label_names, created_at_source, updated_at_source,
          merged_at_source, review_status, review_duration_minutes, comment_rate, defect_count,
          scan_status, scan_bug_count, added_lines, deleted,
          search_text, search_compact, search_spell, search_initials,
          owner_search_text, owner_search_compact, owner_search_spell, owner_search_initials
        ) values (
          'GITLAB', 'default', ?, ?, ?, ?, ?, ?, 'merged', ?, 'feature/sql-pushdown', 'author',
          ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', 30, ?, ?, 'SCANNED', 0, ?, false,
          ?, ?, ?, ?, ?, ?, ?, ?
        )
        """,
        projectId,
        projectName,
        repositoryName,
        mergeRequestId,
        mergeRequestIid,
        title,
        targetBranch,
        ownerName,
        ownerName,
        moduleName,
        labelNames,
        mergedAt.minusDays(1),
        mergedAt,
        mergedAt,
        commentRate,
        defectCount,
        addedLines,
        indexes.keyword().normalized(),
        indexes.keyword().compact(),
        indexes.keyword().spell(),
        indexes.keyword().initials(),
        indexes.owner().normalized(),
        indexes.owner().compact(),
        indexes.owner().spell(),
        indexes.owner().initials());
  }
}
