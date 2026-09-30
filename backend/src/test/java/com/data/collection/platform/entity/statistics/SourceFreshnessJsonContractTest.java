package com.data.collection.platform.entity.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 统计产出的来源新鲜度提示序列化契约。
 *
 * <p>权威产出（完整且最新）必须与历史契约逐字一致：不带 {@code dataAsOf} 与 {@code pendingUpdates}；
 * 只有降级可读的产出才携带两者。若把空值也写进响应，全部既有统计端点的响应体都会增加两个恒为 null
 * 的字段，等同于无声地改变对外契约。
 */
class SourceFreshnessJsonContractTest {
  private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 9, 30, 10, 0);

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void test_authoritative_response_omits_freshness_fields() throws Exception {
    String json = objectMapper.writeValueAsString(response());

    assertThat(json).doesNotContain("dataAsOf").doesNotContain("pendingUpdates");
  }

  @Test
  void test_degraded_response_serializes_data_time_and_pending_count() throws Exception {
    String json = objectMapper.writeValueAsString(response().withPendingUpdates(3L));

    assertThat(json)
        .contains("\"dataAsOf\":\"2026-09-30T10:00:00\"")
        .contains("\"pendingUpdates\":3");
  }

  @Test
  void test_with_pending_updates_keeps_authoritative_response_unwrapped() {
    StatisticBoardResponse response = response();

    assertThat(response.withPendingUpdates(0L)).isSameAs(response);
    StatisticBoardResponse degraded = response.withPendingUpdates(5L);
    assertThat(degraded.pendingUpdates()).isEqualTo(5L);
    assertThat(degraded.dataAsOf()).isEqualTo(GENERATED_AT);
    assertThat(degraded.rows()).isEmpty();
  }

  @Test
  void test_freshness_fields_must_appear_together() {
    assertThatThrownBy(
            () -> new StatisticBoardResponse(null, Map.of(), null, List.of(), null, GENERATED_AT, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须同时包含");
    assertThatThrownBy(
            () -> new StatisticBoardResponse(null, Map.of(), null, List.of(), null, GENERATED_AT, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("正数待更新数量");
  }

  private StatisticBoardResponse response() {
    return new StatisticBoardResponse(
        null,
        Map.of("stage", "system-test"),
        null,
        List.of(),
        new StatisticBoardMeta(GENERATED_AT, 12L, 0, 3, 1),
        null,
        null);
  }
}
