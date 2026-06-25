package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleConfigRequest;
import com.data.collection.platform.service.TextQuerySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LabelGroupDynamicRuleEvaluationServiceTest {
  @Mock private NamedParameterJdbcTemplate jdbcTemplate;

  @Test
  void shouldPreviewDistinctIssueAssigneesFromCurrentDsl() {
    when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of("张三", "李四", "张三", null));

    assertThat(service().preview(issueAssigneeRule()).members())
        .extracting(member -> member.value())
        .containsExactly("张三", "李四");
  }

  @Test
  void shouldMaterializeMembersFromCurrentDsl() {
    when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of("张三"));

    assertThat(service().materializeMembers(issueAssigneeRule()))
        .extracting(LabelGroupMemberRecord::memberValue)
        .containsExactly("张三");
  }

  @Test
  void shouldRejectTooManyOutputValues() {
    List<String> rows = new ArrayList<>();
    for (int index = 0; index < 201; index++) {
      rows.add("用户" + index);
    }
    when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(rows);

    assertThatThrownBy(() -> service().preview(issueAssigneeRule()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("超过 200 个成员");
  }

  private LabelGroupDynamicRuleEvaluationService service() {
    return new LabelGroupDynamicRuleEvaluationService(
        jdbcTemplate,
        new ObjectMapper(),
        new LabelGroupDynamicRuleCatalogService());
  }

  private LabelGroupRuleConfigRequest issueAssigneeRule() {
    return new LabelGroupRuleConfigRequest(
        "issue_fact",
        "assigneeName",
        true,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of(),
        List.of(),
        50);
  }
}
