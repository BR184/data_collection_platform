package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewDataRecordSortSupportTest {

  @Test
  void shouldNormalizeSortFieldAndOrder() {
    assertThat(ReviewDataRecordSortSupport.normalizeSortField(null)).isEqualTo("updatedAt");
    assertThat(ReviewDataRecordSortSupport.normalizeSortField("problemCount")).isEqualTo("problemCount");
    assertThat(ReviewDataRecordSortSupport.normalizeSortField("unsupported")).isEqualTo("updatedAt");
    assertThat(ReviewDataRecordSortSupport.normalizeSortOrder("asc")).isEqualTo("asc");
    assertThat(ReviewDataRecordSortSupport.normalizeSortOrder(null)).isEqualTo("desc");
  }

  @Test
  void shouldBuildComparatorWithStableIdTieBreaker() {
    List<ReviewDataRecordRowResponse> rows =
        new ArrayList<>(
            List.of(
                row(3L, "B", 2),
                row(1L, "A", 2),
                row(2L, "C", 1)));

    rows.sort(ReviewDataRecordSortSupport.buildComparator("problemCount", "asc"));

    assertThat(rows).extracting(ReviewDataRecordRowResponse::id).containsExactly(2L, 1L, 3L);
  }

  private ReviewDataRecordRowResponse row(Long id, String title, Integer problemCount) {
    return new ReviewDataRecordRowResponse(
        id,
        "项目A",
        title,
        "模块A",
        "人工评审",
        LocalDate.of(2026, 4, 20),
        "负责人A",
        "专家A",
        12,
        "产品A",
        "作者A",
        "V1.0",
        problemCount,
        0.25,
        LocalDateTime.of(2026, 4, 20, 10, 0),
        false);
  }
}
