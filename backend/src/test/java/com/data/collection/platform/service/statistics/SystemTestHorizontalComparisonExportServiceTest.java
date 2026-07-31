package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.ReviewDataMatchModeRecordRepository;
import com.data.collection.platform.service.ReviewDataMirrorOptionRepository;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SystemTestHorizontalComparisonExportServiceTest {
  private final RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
  private final ReviewDataMirrorOptionRepository mirrorOptionRepository =
      mock(ReviewDataMirrorOptionRepository.class);
  private final SystemTestHorizontalComparisonExportService exportService =
      new SystemTestHorizontalComparisonExportService(
          jdbcTemplate,
          mock(JsonUtils.class),
          mock(SystemTestPhaseScopeResolver.class),
          mock(CodeReviewMatchModeSwitchService.class),
          mock(ReviewDataMatchModeRecordRepository.class),
          mirrorOptionRepository);

  @Test
  void horizontalExportLoadsAtomicModuleCatalogFromMirroredGitlabLabels() {
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of("工程图", "平台"));

    exportService.exportCsv(Map.of("projectName", "CC2026R4"));

    verify(mirrorOptionRepository).loadModuleNames();
  }

  @Test
  void codeReviewFactsNeverBecomeHorizontalExportModuleCatalog() {
    exportService.exportCsv(Map.of("projectName", "CC2026R4"));

    assertThat(jdbcTemplate.queries())
        .extracting(ModuleQuery::sql)
        .allSatisfy(sql -> assertThat(sql)
            .doesNotContain("from code_review_formal_records")
            .doesNotContain("from code_review_match_mode_records"));
  }

  @Test
  void horizontalExportIncludesHistoricalReviewSnapshotsWhenCompatibilityReadEnabled() {
    ReviewDataMatchModeRecordRepository reviewRepository =
        mock(ReviewDataMatchModeRecordRepository.class);
    when(reviewRepository.loadRecords())
        .thenReturn(
            List.of(
                new ReviewDataRecordRowResponse(
                    -1L,
                    "CC2026R4",
                    "历史需求评审",
                    "历史模块",
                    "需求说明书评审",
                    LocalDate.of(2026, 6, 1),
                    "负责人A",
                    "专家A",
                    10,
                    "需求文档",
                    "作者A",
                    "V1",
                    3,
                    0.3D,
                    LocalDateTime.of(2026, 6, 1, 10, 0),
                    false)));
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);
    when(switchService.isReviewDataCompatibilityReadEnabled()).thenReturn(true);
    SystemTestHorizontalComparisonExportService service =
        new SystemTestHorizontalComparisonExportService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(SystemTestPhaseScopeResolver.class),
            switchService,
            reviewRepository,
            mirrorOptionRepository);

    String csv = service.exportCsv(Map.of("projectName", "CC2026R4"));

    assertThat(csv).contains("历史模块");
  }

  @Test
  void horizontalExportExcludesHistoricalReviewSnapshotsWhenCompatibilityReadDisabled() {
    ReviewDataMatchModeRecordRepository reviewRepository =
        mock(ReviewDataMatchModeRecordRepository.class);
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);
    when(switchService.isReviewDataCompatibilityReadEnabled()).thenReturn(false);
    SystemTestHorizontalComparisonExportService service =
        new SystemTestHorizontalComparisonExportService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(SystemTestPhaseScopeResolver.class),
            switchService,
            reviewRepository,
            mirrorOptionRepository);

    service.exportCsv(Map.of("projectName", "CC2026R4"));

    verifyNoInteractions(reviewRepository);
  }

  @Test
  void issueSummaryUsesContainsMembershipForCompoundPhaseFacts() {
    SystemTestPhaseScopeResolver phaseResolver = mock(SystemTestPhaseScopeResolver.class);
    when(phaseResolver.resolvePhases(9L, "CC2026R4"))
        .thenReturn(List.of("CC2026R4第一轮系统测试", "CC2026R4第二轮系统测试"));
    SystemTestHorizontalComparisonExportService service =
        new SystemTestHorizontalComparisonExportService(
            jdbcTemplate,
            mock(JsonUtils.class),
            phaseResolver,
            mock(CodeReviewMatchModeSwitchService.class),
            mock(ReviewDataMatchModeRecordRepository.class),
            mirrorOptionRepository);

    service.exportCsv(Map.of("testingPhase", "CC2026R4"));

    assertThat(jdbcTemplate.issueQuery()).isNotNull();
    assertThat(jdbcTemplate.issueQuery().sql())
        .contains("testing_phase like ? or testing_phase like ?")
        .doesNotContain("lower(coalesce(testing_phase, '')) = ?");
    assertThat(jdbcTemplate.issueQuery().args())
        .contains("%CC2026R4第一轮系统测试%", "%CC2026R4第二轮系统测试%");
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final List<ModuleQuery> queries = new java.util.ArrayList<>();
    private ModuleQuery issueQuery;

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      queries.add(new ModuleQuery(sql, List.copyOf(Arrays.asList(args))));
      return List.of();
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      if (sql.contains("from issue_fact")) {
        issueQuery = new ModuleQuery(sql, List.copyOf(Arrays.asList(args)));
      }
      return List.of();
    }

    private List<ModuleQuery> queries() {
      return List.copyOf(queries);
    }

    private ModuleQuery issueQuery() {
      return issueQuery;
    }
  }

  private record ModuleQuery(String sql, List<Object> args) {}
}
