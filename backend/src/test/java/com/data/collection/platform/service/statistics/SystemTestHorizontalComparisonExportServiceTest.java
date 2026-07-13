package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.service.CodeReviewDataReadMode;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.ReviewDataMatchModeRecordRepository;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemTestHorizontalComparisonExportServiceTest {
  private final RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
  private final SystemTestHorizontalComparisonExportService exportService =
      new SystemTestHorizontalComparisonExportService(
          jdbcTemplate,
          mock(JsonUtils.class),
          mock(SystemTestPhaseScopeResolver.class),
          mock(CodeReviewMatchModeSwitchService.class),
          mock(ReviewDataMatchModeRecordRepository.class));

  @Test
  void matchModeModuleCatalogReadsCcAndDgmOnlyFromCompatibilitySnapshot() {
    exportService.loadCodeReviewModules("CC2026R4", CodeReviewDataReadMode.MATCH_MODE);

    assertThat(jdbcTemplate.queries()).hasSize(2);
    assertThat(jdbcTemplate.queries())
        .extracting(ModuleQuery::sql)
        .allSatisfy(sql -> {
          assertThat(sql).contains("from code_review_match_mode_records");
          assertThat(sql).doesNotContain("merge_request_fact");
        });
    assertThat(jdbcTemplate.queries().get(0).args())
        .containsExactly("cc", "crowncad", "cc2026r4");
    assertThat(jdbcTemplate.queries().get(1).args())
        .containsExactly("dgm", "dgm", "cc2026r4", "crowncad 2026 r4");
  }

  @Test
  void formalModuleCatalogReadsOnlyDefaultCrownCadFacts() {
    exportService.loadCodeReviewModules("CC2026R4", CodeReviewDataReadMode.FORMAL);

    assertThat(jdbcTemplate.queries()).hasSize(1);
    ModuleQuery query = jdbcTemplate.queries().getFirst();
    assertThat(query.sql())
        .contains("from merge_request_fact")
        .doesNotContain("code_review_match_mode_records");
    assertThat(query.args())
        .containsExactly(
            GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
            SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
            "cc2026r4");
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final List<ModuleQuery> queries = new java.util.ArrayList<>();

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      queries.add(new ModuleQuery(sql, List.copyOf(Arrays.asList(args))));
      return List.of();
    }

    private List<ModuleQuery> queries() {
      return List.copyOf(queries);
    }
  }

  private record ModuleQuery(String sql, List<Object> args) {}
}
