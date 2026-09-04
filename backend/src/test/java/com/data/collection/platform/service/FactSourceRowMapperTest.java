package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarEntry;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarKey;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactSourceRowMapperTest {

  @Test
  void issueMapperShouldPreserveNormalizedFieldsAndCalendarDerivedLegacyFlag()
      throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    Array issueLabels =
        sqlArray(
            "模块：草图",
            "测试阶段：CC2026R4第一轮系统测试",
            "一级缺陷",
            "P1",
            "状态：已修复/完成");
    when(resultSet.getArray("label_titles")).thenReturn(issueLabels);
    when(resultSet.getString("title")).thenReturn("  草图保存失败  ");
    when(resultSet.getString("description")).thenReturn("");
    when(resultSet.getString("notes_text")).thenReturn("");
    when(resultSet.getLong("project_id")).thenReturn(9L);
    when(resultSet.getString("project_name")).thenReturn("CC");
    when(resultSet.getLong("issue_id")).thenReturn(101L);
    when(resultSet.getLong("issue_iid")).thenReturn(77L);
    LocalDateTime createdAt = LocalDateTime.of(2026, 5, 2, 10, 30);
    when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(createdAt));
    when(resultSet.getObject("state_id")).thenReturn(1);
    ModuleDictionary dictionary = mock(ModuleDictionary.class);
    when(dictionary.normalizeIssueModules(9L, List.of("草图")))
        .thenReturn(List.of("草图标准模块"));
    var calendarEntry =
        new PhaseCalendarEntry(
            9L,
            "CC2026R4第一轮系统测试",
            LocalDateTime.of(2026, 5, 1, 0, 0),
            null,
            true);
    Map<PhaseCalendarKey, PhaseCalendarEntry> calendar =
        Map.of(
            new PhaseCalendarKey(9L, "cc2026r4第一轮系统测试"),
            calendarEntry);

    IssueFact fact =
        new IssueFactSourceRowMapper()
            .mapSource(resultSet, "default", calendar, dictionary, Map.of());

    assertThat(fact.getSourceSystem()).isEqualTo("GITLAB");
    assertThat(fact.getSourceInstance()).isEqualTo("default");
    assertThat(fact.getIssueState()).isEqualTo("opened");
    assertThat(fact.getTitle()).isEqualTo("草图保存失败");
    assertThat(fact.getModuleName()).isEqualTo("草图标准模块");
    assertThat(fact.getModuleNames()).isEqualTo("草图标准模块");
    assertThat(fact.getTestingPhase()).isEqualTo("CC2026R4第一轮系统测试");
    assertThat(fact.getSeverityLevel()).isEqualTo("LEVEL1");
    assertThat(fact.getPriorityLevel()).isEqualTo("P1");
    assertThat(fact.getBugStatus()).isEqualTo("已修复/完成");
    assertThat(fact.getCreatedAtSource()).isEqualTo(createdAt);
    assertThat(fact.getDeleted()).isFalse();
  }

  @Test
  void mergeRequestMapperShouldCalculateMissingDerivedMetricsFromRawValues()
      throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    Array mergeRequestLabels = sqlArray("模块：草图", "工具箱：曲线");
    Array projectLabels = sqlArray("项目：CC2026R4");
    when(resultSet.getArray("label_titles")).thenReturn(mergeRequestLabels);
    when(resultSet.getArray("project_label_titles")).thenReturn(projectLabels);
    when(resultSet.getLong("project_id")).thenReturn(9L);
    when(resultSet.getLong("merge_request_id")).thenReturn(501L);
    when(resultSet.getLong("merge_request_iid")).thenReturn(42L);
    when(resultSet.getObject("state_id")).thenReturn(3);
    when(resultSet.getString("title")).thenReturn("  修复草图  ");
    when(resultSet.getObject("review_duration_minutes")).thenReturn(30);
    when(resultSet.getObject("added_lines")).thenReturn(100);
    when(resultSet.getObject("defect_count")).thenReturn(2);
    when(resultSet.getObject("commit_count")).thenReturn(4);
    ModuleDictionary dictionary = mock(ModuleDictionary.class);
    when(dictionary.normalizeMergeRequestModules(9L, List.of("草图", "曲线")))
        .thenReturn(List.of("草图", "曲线"));

    MergeRequestFact fact =
        new MergeRequestFactSourceRowMapper().mapSource(resultSet, "default", dictionary);

    assertThat(fact.getProjectName()).isEqualTo("CC2026R4");
    assertThat(fact.getMergeRequestState()).isEqualTo("merged");
    assertThat(fact.getModuleName()).isEqualTo("草图 & 曲线");
    assertThat(fact.getReviewStatus()).isEqualTo("COMPLETED");
    assertThat(fact.getReviewSpeedLocPerHour()).isEqualTo(200);
    assertThat(fact.getReviewSpeedKlocPerHour()).isEqualByComparingTo("0.20");
    assertThat(fact.getReviewDefectDensityPerKloc()).isEqualByComparingTo("20.00");
    assertThat(fact.getReviewEfficiencyPerHour()).isEqualByComparingTo("4.00");
    assertThat(fact.getCommitRate()).isEqualTo(25);
    assertThat(fact.getDeleted()).isFalse();
  }

  @Test
  void mergeRequestCommitMapperShouldPreserveNullableIidAndSourceTimestamp()
      throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getLong("project_id")).thenReturn(9L);
    when(resultSet.getLong("merge_request_id")).thenReturn(501L);
    when(resultSet.getObject("merge_request_iid")).thenReturn(42L);
    when(resultSet.getString("commit_sha")).thenReturn("abc123");
    LocalDateTime committedAt = LocalDateTime.of(2026, 5, 2, 11, 30);
    when(resultSet.getTimestamp("committed_at_source"))
        .thenReturn(Timestamp.valueOf(committedAt));

    MergeRequestCommitFact fact =
        new MergeRequestFactSourceRowMapper().mapCommit(resultSet, "secondary");

    assertThat(fact.sourceSystem()).isEqualTo("GITLAB");
    assertThat(fact.sourceInstance()).isEqualTo("secondary");
    assertThat(fact.mergeRequestIid()).isEqualTo(42L);
    assertThat(fact.commitSha()).isEqualTo("abc123");
    assertThat(fact.committedAtSource()).isEqualTo(committedAt);
  }

  private Array sqlArray(String... values) throws Exception {
    Array array = mock(Array.class);
    when(array.getArray()).thenReturn(values);
    return array;
  }
}
