package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.OptionItemResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CodeReviewMultiBoardServiceTest {

  @Mock private JdbcTemplate jdbcTemplate;

  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport =
      mock(QualityBoardCodeReviewReadSupport.class);

  private CodeReviewMultiBoardService service;

  @BeforeEach
  void setUp() {
    service = new CodeReviewMultiBoardService(jdbcTemplate, codeReviewReadSupport);
  }

  @Test
  void matchModeSourceOptionsComeOnlyFromIsolatedReadSupport() {
    org.mockito.Mockito.when(codeReviewReadSupport.listAvailableSources())
        .thenReturn(List.of(new OptionItemResponse("CC", "cc"), new OptionItemResponse("DGM", "dgm")));

    List<OptionItemResponse> options = service.listSourceOptions();

    assertThat(options).extracting(OptionItemResponse::value).containsExactly("cc", "dgm");
    assertThat(options).extracting(OptionItemResponse::label).containsExactly("CC", "DGM");
  }

  @Test
  void formalModeDoesNotUnionCompatibilitySources() {
    when(codeReviewReadSupport.listAvailableSources())
        .thenReturn(List.of(new OptionItemResponse("CC", "cc")));

    List<OptionItemResponse> options = service.listSourceOptions();

    assertThat(options).extracting(OptionItemResponse::value).containsExactly("cc");
    assertThat(options).extracting(OptionItemResponse::label).containsExactly("CC");
  }

  @Test
  void legacyOverviewKeepsTheWholeCurrentSourceInsteadOfSelectingTheFirstProject() {
    var capturingJdbc = new CapturingJdbcTemplate();
    service = new CodeReviewMultiBoardService(capturingJdbc, codeReviewReadSupport);
    var readScope =
        new QualityBoardCodeReviewReadScope(
            true,
            "merge_request_fact",
            "default",
            List.of(),
            "project_id, merge_request_id",
            " and deleted = false",
            "project_id = ?",
            List.of(9L));
    when(codeReviewReadSupport.configuredReadMode()).thenReturn(CodeReviewDataReadMode.FORMAL);
    when(codeReviewReadSupport.listAvailableSources(CodeReviewDataReadMode.FORMAL))
        .thenReturn(List.of(new OptionItemResponse("CC", "cc")));
    when(codeReviewReadSupport.resolveAllProjectsScope("cc", CodeReviewDataReadMode.FORMAL))
        .thenReturn(readScope);
    when(codeReviewReadSupport.queryScope(readScope))
        .thenReturn(
            new QualityBoardCodeReviewQueryScope(
                "lower(coalesce(source_instance, '')) = ? and project_id = ?",
                List.of("default", 9L)));

    service.getOverview(new CodeReviewMultiBoardOverviewRequest("cc"));

    verify(codeReviewReadSupport, never())
        .listProjectOptions("cc", CodeReviewDataReadMode.FORMAL);
    verify(codeReviewReadSupport)
        .resolveAllProjectsScope("cc", CodeReviewDataReadMode.FORMAL);
    assertThat(capturingJdbc.sqlStatements).hasSize(3);
    assertThat(capturingJdbc.sqlStatements)
        .allSatisfy(
            sql ->
                assertThat(sql)
                    .contains("project_id = ?")
                    .doesNotContain("project_name in")
                    .doesNotContain("nullif(btrim(coalesce(project_name"));
  }

  private static final class CapturingJdbcTemplate extends JdbcTemplate {
    private final List<String> sqlStatements = new ArrayList<>();

    @Override
    public Map<String, Object> queryForMap(String sql, Object... args) {
      sqlStatements.add(sql);
      return Map.of(
          "merge_request_count", 0,
          "completed_count", 0,
          "pending_count", 0,
          "total_defect_count", 0,
          "total_added_lines", 0);
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
      sqlStatements.add(sql);
      return List.of();
    }
  }

}
