package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.OptionItemResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
