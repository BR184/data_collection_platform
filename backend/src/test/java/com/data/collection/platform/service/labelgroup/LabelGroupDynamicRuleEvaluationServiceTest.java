package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.IssueFactRecord;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LabelGroupDynamicRuleEvaluationServiceTest {
  @Mock private IssueFactRecordRepository issueFactRecordRepository;

  @Test
  void shouldPreviewRecentActiveAssigneesFromSystemTestFacts() {
    when(issueFactRecordRepository.findByFilters(Map.of()))
        .thenReturn(
            List.of(
                issue("张三", true, false, LocalDateTime.now().minusDays(1)),
                issue("李四", true, false, LocalDateTime.now().minusDays(2)),
                issue("王五", false, false, LocalDateTime.now().minusDays(1)),
                issue("赵六", true, false, LocalDateTime.now().minusDays(40))));

    LabelGroupDynamicRuleEvaluationService service = service();

    assertThat(service.preview("recent-active-assignee", "{\"days\":30,\"scope\":\"system-test\"}").members())
        .extracting(member -> member.value())
        .containsExactly("张三", "李四");
  }

  @Test
  void shouldPreviewDelayedAssignees() {
    when(issueFactRecordRepository.findByFilters(Map.of()))
        .thenReturn(
            List.of(
                issue("张三", true, true, LocalDateTime.now().minusDays(1)),
                issue("李四", true, false, LocalDateTime.now().minusDays(1))));

    LabelGroupDynamicRuleEvaluationService service = service();

    assertThat(service.materializeMembers("current-version-delayed-assignee", "{\"scope\":\"system-test\"}"))
        .extracting(LabelGroupMemberRecord::memberValue)
        .containsExactly("张三");
  }

  @Test
  void shouldRejectTooManyOutputValues() {
    List<IssueFactRecord> rows = new ArrayList<>();
    for (int index = 0; index < 201; index++) {
      rows.add(issue("用户" + index, true, false, LocalDateTime.now().minusDays(1)));
    }
    when(issueFactRecordRepository.findByFilters(Map.of())).thenReturn(rows);

    assertThatThrownBy(() -> service().preview("recent-active-assignee", "{\"days\":30,\"scope\":\"system-test\"}"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("超过 200 个成员");
  }

  private LabelGroupDynamicRuleEvaluationService service() {
    return new LabelGroupDynamicRuleEvaluationService(issueFactRecordRepository, new ObjectMapper());
  }

  private static IssueFactRecord issue(String assigneeName, boolean systemTest, boolean delayed, LocalDateTime updatedAt) {
    return new IssueFactRecord(
        1L,
        "default",
        systemTest ? "CC2026R1" : "CC_Product",
        1L,
        1,
        "标题",
        "opened",
        systemTest ? "CC2026R1第一轮系统测试" : "",
        "",
        "一级缺陷",
        "",
        "",
        "",
        "",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "",
        "",
        assigneeName,
        List.of(),
        systemTest ? List.of("系统测试") : List.of(),
        delayed,
        "",
        delayed ? "技术卡点" : "",
        false,
        false,
        false,
        "",
        LocalDateTime.now().minusDays(10),
        updatedAt,
        null);
  }
}
