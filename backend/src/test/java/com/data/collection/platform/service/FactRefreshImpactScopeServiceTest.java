package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FactRefreshImpactScopeServiceTest {

  @Test
  void test_deleted_issue_assignee_scope_remains_in_issue_fact_impact() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    FactRefreshImpactScopeService service =
        new FactRefreshImpactScopeService(jdbcTemplate, taskMapper);
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(501L);
    task.setRunId(77L);
    task.setSourceTable("issue_assignees");
    task.setStatus(SyncRunStatus.SUCCESS);
    task.setRowsApplied(1L);
    when(taskMapper.selectList(any())).thenReturn(List.of(task));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(501L)))
        .thenReturn(List.of(new FactRefreshImpactScopeService.Target(9L, 101L)));

    FactRefreshImpactScopeService.ImpactScope result =
        service.resolve(77L, "default", "ISSUE");

    assertThat(result.fallbackRequired()).isFalse();
    assertThat(result.targets())
        .containsExactly(new FactRefreshImpactScopeService.Target(9L, 101L));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbcTemplate)
        .query(sqlCaptor.capture(), any(RowMapper.class), eq(501L));
    assertThat(sqlCaptor.getValue()).doesNotContain("ia.mirror_deleted");
  }

  @Test
  void test_deleted_label_link_remains_in_issue_fact_impact() {
    assertDeletedRelationParticipates("label_links", "ISSUE", "Issue", "ll.mirror_deleted");
  }

  @Test
  void test_deleted_label_link_remains_in_merge_request_fact_impact() {
    assertDeletedRelationParticipates(
        "label_links", "MERGE_REQUEST", "MergeRequest", "ll.mirror_deleted");
  }

  @Test
  void test_deleted_merge_request_reviewer_remains_in_fact_impact() {
    assertDeletedRelationParticipates(
        "merge_request_reviewers", "MERGE_REQUEST", null, "jt.mirror_deleted");
  }

  @Test
  void test_issue_label_link_drives_integration_test_fact_impact() {
    assertDeletedRelationParticipates(
        "label_links", "INTEGRATION_TEST", "Issue", "ll.mirror_deleted");
  }

  private void assertDeletedRelationParticipates(
      String sourceTable, String factType, String expectedTargetType, String deletedPredicate) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    FactRefreshImpactScopeService service =
        new FactRefreshImpactScopeService(jdbcTemplate, taskMapper);
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(501L);
    task.setRunId(77L);
    task.setSourceTable(sourceTable);
    task.setStatus(SyncRunStatus.SUCCESS);
    task.setRowsApplied(1L);
    when(taskMapper.selectList(any())).thenReturn(List.of(task));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(501L)))
        .thenReturn(List.of(new FactRefreshImpactScopeService.Target(9L, 101L)));

    FactRefreshImpactScopeService.ImpactScope result =
        service.resolve(77L, "default", factType);

    assertThat(result.fallbackRequired()).isFalse();
    assertThat(result.targets())
        .containsExactly(new FactRefreshImpactScopeService.Target(9L, 101L));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbcTemplate)
        .query(sqlCaptor.capture(), any(RowMapper.class), eq(501L));
    assertThat(sqlCaptor.getValue()).doesNotContain(deletedPredicate);
    if (expectedTargetType != null) {
      assertThat(sqlCaptor.getValue()).contains("target_type = '" + expectedTargetType + "'");
    }
  }
}
