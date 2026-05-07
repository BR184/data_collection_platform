package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class CodeReviewIllegalRecordSourceLoaderTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private CodeReviewIllegalRecordSourceLoader sourceLoader;

  @MockitoBean private FactBuildService factBuildService;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from merge_request_fact");
  }

  @Test
  void shouldFilterMergeRequestFactsBySourceInstance() {
    insertMergeRequestFact(
        "cc",
        325L,
        "Product Center",
        "repo-a",
        9201L,
        21L,
        "cc missing owner",
        "master",
        "feature/cc",
        "Alice",
        "",
        "payment",
        "payment",
        LocalDateTime.of(2026, 4, 16, 9, 30),
        "DONE",
        37,
        "SCANNED",
        0,
        0.75,
        3,
        128,
        false);
    insertMergeRequestFact(
        "dgm",
        325L,
        "Product Center",
        "repo-a",
        9202L,
        22L,
        "dgm missing owner",
        "master",
        "feature/dgm",
        "Bob",
        "",
        "payment",
        "payment",
        LocalDateTime.of(2026, 4, 17, 9, 30),
        "DONE",
        37,
        "SCANNED",
        0,
        0.75,
        3,
        128,
        false);

    List<CodeReviewIllegalRecordSource> sources =
        sourceLoader.loadSources(Map.of("sourceInstance", "DGM"));

    assertThat(sources).hasSize(1);
    assertThat(sources.getFirst().mergeRequestId()).isEqualTo(9202L);
  }

  @Test
  void shouldLoadMergeRequestFactsWithCombinedFiltersAndFieldMapping() {
    insertMergeRequestFact(
        325L,
        "Product Center",
        "repo-a",
        9001L,
        88L,
        "payment module refactor",
        "master",
        "feature/payment",
        "Alice",
        "Owner A",
        "payment",
        "payment, backend",
        LocalDateTime.of(2026, 4, 16, 9, 30),
        "DONE",
        37,
        "SCANNED",
        2,
        0.75,
        3,
        128,
        false);
    insertMergeRequestFact(
        325L,
        "Product Center",
        "repo-b",
        9002L,
        89L,
        "login api cleanup",
        "master",
        "feature/login",
        "Bob",
        "Owner A",
        "login",
        "login",
        LocalDateTime.of(2026, 4, 17, 9, 30),
        "DONE",
        12,
        "SCANNED",
        0,
        0.2,
        0,
        45,
        false);
    insertMergeRequestFact(
        325L,
        "Product Center",
        "repo-a",
        9003L,
        88L,
        "deleted duplicate",
        "master",
        "feature/payment-old",
        "Carol",
        "Owner A",
        "payment",
        "payment",
        LocalDateTime.of(2026, 4, 18, 9, 30),
        "DONE",
        9,
        "SCANNED",
        1,
        0.1,
        1,
        20,
        true);

    List<CodeReviewIllegalRecordSource> sources =
        sourceLoader.loadSources(
            Map.of(
                "projectId", "325",
                "projectName", "Product",
                "repositoryName", "repo-a",
                "targetBranch", "master",
                "moduleName", "payment",
                "owner", "Owner A",
                "mergeRequestIid", "88",
                "mergedAtStart", "2026-04-01",
                "mergedAtEnd", "2026-04-30"));

    assertThat(sources).hasSize(1);
    CodeReviewIllegalRecordSource source = sources.getFirst();
    assertThat(source.mergeRequestId()).isEqualTo(9001L);
    assertThat(source.mergeRequestIid()).isEqualTo(88);
    assertThat(source.projectId()).isEqualTo(325L);
    assertThat(source.mergeRequestContent()).isEqualTo("payment module refactor");
    assertThat(source.projectName()).isEqualTo("Product Center");
    assertThat(source.repositoryName()).isEqualTo("repo-a");
    assertThat(source.mergedAt()).isEqualTo(LocalDateTime.of(2026, 4, 16, 9, 30));
    assertThat(source.mergedBy()).isEqualTo("Alice");
    assertThat(source.owner()).isEqualTo("Owner A");
    assertThat(source.targetBranch()).isEqualTo("master");
    assertThat(source.moduleName()).isEqualTo("payment");
    assertThat(source.labelTitles()).containsExactly("payment", "backend");
    assertThat(source.reviewStatus()).isEqualTo("DONE");
    assertThat(source.reviewDurationMinutes()).isEqualTo(37);
    assertThat(source.scanStatus()).isEqualTo("SCANNED");
    assertThat(source.scanBugCount()).isEqualTo(2);
    assertThat(source.commentRate()).isEqualTo(0.75);
    assertThat(source.defectCount()).isEqualTo(3);
    assertThat(source.addedLines()).isEqualTo(128);
    verifyNoInteractions(factBuildService);
  }

  @Test
  void shouldLoadDefaultIllegalPageWithSqlFilteringAndPaging() {
    insertMergeRequestFact(
        325L,
        "Product Center",
        "repo-a",
        9101L,
        11L,
        "missing owner",
        "master",
        "feature/a",
        "Alice",
        "",
        "payment",
        "payment",
        LocalDateTime.of(2026, 4, 16, 9, 30),
        "DONE",
        37,
        "SCANNED",
        0,
        0.75,
        3,
        128,
        false);
    insertMergeRequestFact(
        325L,
        "Product Center",
        "repo-a",
        9102L,
        12L,
        "legal row",
        "master",
        "feature/b",
        "Bob",
        "Owner B",
        "payment",
        "payment",
        LocalDateTime.of(2026, 4, 17, 9, 30),
        "DONE",
        12,
        "SCANNED",
        0,
        0.2,
        0,
        45,
        false);

    PageSlice<CodeReviewIllegalRecordSource> page =
        sourceLoader.loadDefaultIllegalPage(
            new CodeReviewIllegalRecordSourcePageQuery(
                new CodeReviewIllegalRecordQueryRequest(
                    325L,
                    "repo-a",
                    null,
                    null,
                    null,
                    null,
                    "merge_request",
                    null,
                    null,
                    null,
                    CodeReviewIllegalRuleRegistry.MISSING_OWNER_LABEL,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "mergeRequestIid",
                    "asc",
                    null),
                null,
                1,
                20,
                "mergeRequestIid",
                "asc"));

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.records()).extracting(CodeReviewIllegalRecordSource::mergeRequestIid).containsExactly(11);
    verifyNoInteractions(factBuildService);
  }

  private void insertMergeRequestFact(
      Long projectId,
      String projectName,
      String repositoryName,
      Long mergeRequestId,
      Long mergeRequestIid,
      String title,
      String targetBranch,
      String sourceBranch,
      String mergeUserName,
      String ownerName,
      String moduleName,
      String labelNames,
      LocalDateTime mergedAtSource,
      String reviewStatus,
      Integer reviewDurationMinutes,
      String scanStatus,
      Integer scanBugCount,
      Double commentRate,
      Integer defectCount,
      Integer addedLines,
      boolean deleted) {
    insertMergeRequestFact(
        "test",
        projectId,
        projectName,
        repositoryName,
        mergeRequestId,
        mergeRequestIid,
        title,
        targetBranch,
        sourceBranch,
        mergeUserName,
        ownerName,
        moduleName,
        labelNames,
        mergedAtSource,
        reviewStatus,
        reviewDurationMinutes,
        scanStatus,
        scanBugCount,
        commentRate,
        defectCount,
        addedLines,
        deleted);
  }

  private void insertMergeRequestFact(
      String sourceInstance,
      Long projectId,
      String projectName,
      String repositoryName,
      Long mergeRequestId,
      Long mergeRequestIid,
      String title,
      String targetBranch,
      String sourceBranch,
      String mergeUserName,
      String ownerName,
      String moduleName,
      String labelNames,
      LocalDateTime mergedAtSource,
      String reviewStatus,
      Integer reviewDurationMinutes,
      String scanStatus,
      Integer scanBugCount,
      Double commentRate,
      Integer defectCount,
      Integer addedLines,
      boolean deleted) {
    jdbcTemplate.update(
        """
        insert into merge_request_fact(
          source_system,
          source_instance,
          project_id,
          project_name,
          repository_name,
          merge_request_id,
          merge_request_iid,
          title,
          merge_request_state,
          target_branch,
          source_branch,
          author_name,
          merge_user_name,
          owner_name,
          reviewer_names,
          assignee_names,
          module_name,
          label_names,
          created_at_source,
          updated_at_source,
          merged_at_source,
          review_status,
          review_duration_minutes,
          comment_rate,
          defect_count,
          scan_status,
          scan_bug_count,
          added_lines,
          deleted
        ) values (
          'GITLAB',
          ?,
          ?, ?, ?, ?, ?, ?, 'merged', ?, ?, 'Author', ?, ?, 'Reviewer', 'Assignee', ?, ?,
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """,
        sourceInstance,
        projectId,
        projectName,
        repositoryName,
        mergeRequestId,
        mergeRequestIid,
        title,
        targetBranch,
        sourceBranch,
        mergeUserName,
        ownerName,
        moduleName,
        labelNames,
        mergedAtSource.minusDays(2),
        mergedAtSource.minusDays(1),
        mergedAtSource,
        reviewStatus,
        reviewDurationMinutes,
        commentRate,
        defectCount,
        scanStatus,
        scanBugCount,
        addedLines,
        deleted);
  }
}
