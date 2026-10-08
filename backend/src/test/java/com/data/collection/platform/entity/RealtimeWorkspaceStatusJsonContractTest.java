package com.data.collection.platform.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 实时工作区状态响应的提交字段序列化契约。
 *
 * <p>读取状态（GET /status）不得出现 {@code submissionOutcome} 与 {@code trackingId}：既有读端点与黄金基线
 * 快照必须逐字保持原样，这两个字段只属于 POST 刷新的提交结论，不能在读响应里恒为 null 地外泄。
 */
class RealtimeWorkspaceStatusJsonContractTest {
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void test_read_status_response_omits_submission_fields() throws Exception {
    String json = objectMapper.writeValueAsString(readStatus());

    assertThat(json).doesNotContain("submissionOutcome").doesNotContain("trackingId");
  }

  @Test
  void test_refresh_submission_response_carries_outcome_and_tracking_identity() throws Exception {
    String json = objectMapper.writeValueAsString(
        readStatus().withRefreshSubmit(
            RealtimeWorkspaceStatusResponse.SUBMISSION_ACCEPTED,
            "system-test-defect-summary#1",
            "已开始刷新最新数据"));

    assertThat(json)
        .contains("\"submissionOutcome\":\"ACCEPTED\"")
        .contains("\"trackingId\":\"system-test-defect-summary#1\"");
  }

  @Test
  void test_refresh_submission_keeps_existing_message_when_message_absent() {
    RealtimeWorkspaceStatusResponse submitted =
        readStatus().withRefreshSubmit(RealtimeWorkspaceStatusResponse.SUBMISSION_COOLDOWN, null, null);

    assertThat(submitted.message()).isEqualTo("已展示当前可用数据");
    assertThat(submitted.submissionOutcome())
        .isEqualTo(RealtimeWorkspaceStatusResponse.SUBMISSION_COOLDOWN);
    assertThat(submitted.trackingId()).isNull();
  }

  private RealtimeWorkspaceStatusResponse readStatus() {
    return new RealtimeWorkspaceStatusResponse(
        "system-test-defect-summary",
        true,
        "READY",
        "已展示当前可用数据",
        false,
        LocalDateTime.of(2026, 9, 29, 12, 44),
        null,
        null);
  }
}
