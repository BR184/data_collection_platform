package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.OptionItemResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class QualityBoardCodeReviewReadSupportTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final CodeReviewMatchModeSwitchService matchModeSwitchService =
      mock(CodeReviewMatchModeSwitchService.class);
  private final QualityBoardCodeReviewReadSupport support =
      new QualityBoardCodeReviewReadSupport(jdbcTemplate, matchModeSwitchService);

  @Test
  void compatibilityDgmUsesLegacySnapshotAliasesAndMergeRequestIid() {
    when(matchModeSwitchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(true);

    QualityBoardCodeReviewReadScope scope = support.resolveScope("dgm", "CC2026R4");

    assertThat(scope.available()).isTrue();
    assertThat(scope.tableName()).isEqualTo("code_review_match_mode_records");
    assertThat(scope.sourceInstance()).isEqualTo("dgm");
    assertThat(scope.projectNames()).containsExactly("CC2026R4", "CrownCAD 2026 R4");
    assertThat(scope.additionalPredicate())
        .isEqualTo("lower(btrim(coalesce(repository_name, ''))) = ?");
    assertThat(scope.additionalArgs()).containsExactly("dgm");
    assertThat(scope.mergeRequestIdentity()).isEqualTo("merge_request_iid");
    assertThat(scope.deletedPredicate()).isEmpty();
  }

  @Test
  void formalCcUsesUnifiedFormalCodeReviewSource() {
    when(matchModeSwitchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);

    QualityBoardCodeReviewReadScope scope = support.resolveScope("cc", "CC2026R4");

    assertThat(scope.available()).isTrue();
    assertThat(scope.tableName()).isEqualTo("code_review_formal_records");
    assertThat(scope.sourceInstance()).isEqualTo("cc");
    assertThat(scope.projectNames()).containsExactly("CC2026R4");
    assertThat(scope.additionalPredicate()).isEmpty();
    assertThat(scope.additionalArgs()).isEmpty();
    assertThat(scope.mergeRequestIdentity()).isEqualTo("project_id, merge_request_id");
    assertThat(scope.deletedPredicate()).isEmpty();

    QualityBoardCodeReviewQueryScope queryScope = support.queryScope(scope);
    assertThat(queryScope.predicate())
        .isEqualTo(
            "lower(coalesce(business_source, '')) = ? and coalesce(project_name, '') in (?)");
    assertThat(queryScope.args()).containsExactly("cc", "CC2026R4");
  }

  @Test
  void compatibilityCcAllProjectsStayInsideLegacyCrownCadRepository() {
    QualityBoardCodeReviewReadScope scope =
        support.resolveAllProjectsScope("cc", CodeReviewDataReadMode.MATCH_MODE);

    assertThat(scope.available()).isTrue();
    assertThat(scope.tableName()).isEqualTo("code_review_match_mode_records");
    assertThat(scope.projectNames()).isEmpty();
    assertThat(scope.additionalPredicate())
        .isEqualTo("lower(btrim(coalesce(repository_name, ''))) = ?");
    assertThat(scope.additionalArgs()).containsExactly("crowncad");
    assertThat(support.queryScope(scope).args()).containsExactly("cc", "crowncad");
  }

  @Test
  void formalModeExposesDgmFromUnifiedFormalCodeReviewSource() {
    when(matchModeSwitchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);

    QualityBoardCodeReviewReadScope scope = support.resolveScope("dgm", "CC2026R4");
    List<OptionItemResponse> options = support.listAvailableSources();

    assertThat(scope.available()).isTrue();
    assertThat(scope.tableName()).isEqualTo("code_review_formal_records");
    assertThat(scope.sourceInstance()).isEqualTo("dgm");
    assertThat(scope.projectNames()).containsExactly("CC2026R4", "CrownCAD 2026 R4");
    assertThat(options).extracting(OptionItemResponse::value).containsExactly("cc", "dgm");
  }

  @Test
  void functionDensityExcludesMergeRequestsMarkedAsNoCodeReview() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    QualityBoardCodeReviewReadSupport readSupport =
        new QualityBoardCodeReviewReadSupport(jdbc, matchModeSwitchService);

    readSupport.reviewedAddedLinesByFunction("CC2026R4");

    assertThat(jdbc.lastSql())
        .contains("coalesce(scan_status, '') <> '无需代码走查'");
  }

  @Test
  void qualityRankingAuthorDenominatorIncludesAllMergedCode() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    QualityBoardCodeReviewReadSupport readSupport =
        new QualityBoardCodeReviewReadSupport(jdbc, matchModeSwitchService);

    readSupport.addedLinesByAuthorAcrossAllProjects();

    assertThat(jdbc.lastSql())
        .doesNotContain("coalesce(scan_status, '') <> '无需代码走查'");
  }

  @Test
  void productVersionSpUsesOnlyExactReleaseBranchAndKeepsSpNumbersDistinct() {
    QualityBoardCodeReviewReadScope sp1 = support.resolveProductVersionScope(
        "cc", "CC2026R4SP1", CodeReviewDataReadMode.MATCH_MODE);
    QualityBoardCodeReviewReadScope sp2 = support.resolveProductVersionScope(
        "cc", "CC2026R4SP2", CodeReviewDataReadMode.MATCH_MODE);

    assertThat(sp1.additionalPredicate()).contains("target_branch");
    assertThat(sp1.additionalArgs()).contains("release_2026R4_SP1");
    assertThat(sp2.additionalArgs()).contains("release_2026R4_SP2");
    assertThat(sp1.additionalArgs()).doesNotContain("dev");
  }

  @Test
  void productVersionMembersUseWholeTokensAndSupportInheritedYears() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    QualityBoardCodeReviewReadSupport readSupport =
        new QualityBoardCodeReviewReadSupport(jdbc, matchModeSwitchService);

    QualityBoardCodeReviewReadScope scope = readSupport.resolveProductVersionScope(
        "cc", "CC2027R2", CodeReviewDataReadMode.MATCH_MODE);

    assertThat(scope.available()).isTrue();
    assertThat(scope.projectNames()).containsExactly("CC2027R1&R2");
  }

  @Test
  void emptyProductVersionCreatesUnavailableScopeInsteadOfAllProjects() {
    QualityBoardCodeReviewReadScope scope = support.resolveProductVersionScope(
        "cc", "  ", CodeReviewDataReadMode.MATCH_MODE);

    assertThat(scope.available()).isFalse();
    assertThat(scope.projectNames()).isEmpty();
  }

  @Test
  void nonCanonicalProductKeyKeepsExactProjectNameSemantics() {
    QualityBoardCodeReviewReadScope scope = support.resolveProductVersionScope(
        "cc", "custom", CodeReviewDataReadMode.FORMAL);

    assertThat(scope.available()).isTrue();
    assertThat(scope.projectNames()).containsExactly("custom");
    assertThat(support.queryScope(scope).args()).contains("custom");
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private String lastSql;

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> resultSetExtractor, Object... args) {
      lastSql = sql;
      return null;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      lastSql = sql;
      @SuppressWarnings("unchecked")
      List<T> names = (List<T>) List.of(
          "CC2027R1&R2", "CC2027R10", "Other2027R1", "CC2027R1SP1");
      return names;
    }

    private String lastSql() {
      return lastSql;
    }
  }
}
