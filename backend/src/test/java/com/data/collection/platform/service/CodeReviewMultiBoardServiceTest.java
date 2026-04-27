package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.OptionItemResponse;
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

  @Mock
  private JdbcTemplate jdbcTemplate;

  private CodeReviewMultiBoardService service;

  @BeforeEach
  void setUp() {
    service = new CodeReviewMultiBoardService(jdbcTemplate);
  }

  @Test
  void shouldPreferCcAndDgmSourceOptions() {
    when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
        .thenReturn(List.of("default", "dgm", "cc"));

    List<OptionItemResponse> options = service.listSourceOptions();

    assertThat(options).extracting(OptionItemResponse::value).containsExactly("cc", "dgm");
    assertThat(options).extracting(OptionItemResponse::label).containsExactly("CC", "DGM");
  }

  @Test
  void shouldBuildOverviewForRequestedSource() {
    when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
        .thenReturn(List.of("cc", "dgm"));
    when(jdbcTemplate.queryForMap(anyString(), eq("dgm")))
        .thenReturn(
            Map.of(
                "merge_request_count", 6,
                "completed_count", 4,
                "pending_count", 2,
                "average_comment_rate", 21.35,
                "total_defect_count", 9,
                "average_review_duration_minutes", 18.5,
                "average_added_lines", 64.2));
    when(
            jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("coalesce(nullif(btrim(module_name)"),
                eq("未标注模块"),
                eq("dgm")))
        .thenReturn(
            List.of(
                Map.of(
                    "row_label", "支付中心",
                    "merge_request_count", 3,
                    "completed_count", 2,
                    "average_comment_rate", 20.0,
                    "total_defect_count", 4,
                    "average_review_duration_minutes", 16.0,
                    "average_added_lines", 52.0)));
    when(
            jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("coalesce(nullif(btrim(owner_name)"),
                eq("未标注责任人"),
                eq("dgm")))
        .thenReturn(
            List.of(
                Map.of(
                    "row_label", "张三",
                    "merge_request_count", 2,
                    "completed_count", 2,
                    "average_comment_rate", 22.5,
                    "total_defect_count", 1,
                    "average_review_duration_minutes", 14.0,
                    "average_added_lines", 48.0)));

    CodeReviewMultiBoardOverviewResponse overview = service.getOverview("dgm");

    assertThat(overview.source()).isEqualTo("dgm");
    assertThat(overview.sourceLabel()).isEqualTo("DGM");
    assertThat(overview.mergeRequestCount()).isEqualTo(6);
    assertThat(overview.moduleRows()).hasSize(1);
    assertThat(overview.ownerRows()).hasSize(1);
    assertThat(overview.moduleRows().getFirst().rowLabel()).isEqualTo("支付中心");
  }
}
