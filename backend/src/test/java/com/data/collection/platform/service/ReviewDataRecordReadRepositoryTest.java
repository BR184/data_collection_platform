package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.TagSelectionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class ReviewDataRecordReadRepositoryTest {

  @Test
  void shouldPassSourceInstanceWhenBuildingReviewDataTagSelectionSql() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    TagSelectionSqlPredicateService tagSelectionSqlPredicateService =
        mock(TagSelectionSqlPredicateService.class);
    List<TagSelectionRequest> tagSelections =
        List.of(new TagSelectionRequest("module", List.of("sketch")));
    when(tagSelectionSqlPredicateService.toSql("review_data", "cc", tagSelections))
        .thenReturn(java.util.Optional.of(new SqlPredicate("lower(coalesce(r.module_name, '')) = ?", List.of("sketch"))));
    when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenReturn(
            new ReviewDataRecordReadRepository.RecordPageResult(
                List.of(), 0, new com.data.collection.platform.entity.ReviewDataSummaryResponse(0, 0, 0, 0)));

    new ReviewDataRecordReadRepository(jdbcTemplate, tagSelectionSqlPredicateService)
        .loadRecordPage(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            tagSelections,
            "cc",
            1,
            20,
            "updatedAt",
            "desc");

    verify(tagSelectionSqlPredicateService).toSql(eq("review_data"), eq("cc"), eq(tagSelections));
  }
}
