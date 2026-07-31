package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FactRefreshImpactScopeServiceTest {
  private static final long CONFIG_ID = 1L;
  private static final long MIRROR_RUN_ID = 100L;
  private static final long FACT_RUN_ID = 101L;

  @BeforeAll
  static void initializeMybatisMetadata() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "fact-impact-test"),
        SyncRunTableTask.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = SyncRunType.class,
      names = {"INCREMENTAL_SYNC", "TABLE_REFRESH", "SYSTEM_HOOK", "COMPENSATION_SCAN"})
  void test_fact_child_resolves_issue_targets_from_authoritative_mirror_parent(
      SyncRunType parentRunType) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = validRunMapper(parentRunType, "default");
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);
    SyncRunTableTask task = successfulTask("label_links");
    when(taskMapper.selectList(any())).thenReturn(List.of(task));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(501L)))
        .thenReturn(List.of(new FactRefreshImpactScopeService.Target(9L, 101L)));

    FactRefreshImpactScopeService.ImpactScope result =
        service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE");

    assertThat(result.fallbackRequired()).isFalse();
    assertThat(result.targets())
        .containsExactly(new FactRefreshImpactScopeService.Target(9L, 101L));
    org.mockito.Mockito.verify(runMapper).selectById(FACT_RUN_ID);
    org.mockito.Mockito.verify(runMapper).selectById(MIRROR_RUN_ID);
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> wrapperCaptor =
        ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
    org.mockito.Mockito.verify(taskMapper).selectList(wrapperCaptor.capture());
    com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper =
        (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
    wrapper.getSqlSegment();
    assertThat(wrapper.getParamNameValuePairs().values())
        .contains(MIRROR_RUN_ID)
        .doesNotContain(FACT_RUN_ID);
  }

  @Test
  void test_missing_fact_child_fails_instead_of_returning_empty_impact() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("事实刷新运行")
        .hasMessageContaining("101");
  }

  @Test
  void test_non_fact_child_fails_lineage_validation() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    when(runMapper.selectById(FACT_RUN_ID))
        .thenReturn(run(FACT_RUN_ID, SyncRunType.INCREMENTAL_SYNC, null, CONFIG_ID, "default"));
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FACT_REFRESH");
  }

  @Test
  void test_fact_child_config_or_source_mismatch_fails_lineage_validation() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    when(runMapper.selectById(FACT_RUN_ID))
        .thenReturn(run(FACT_RUN_ID, SyncRunType.FACT_REFRESH, MIRROR_RUN_ID, 2L, "other"));
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("配置或数据源不一致");
  }

  @Test
  void test_missing_mirror_parent_fails_instead_of_returning_empty_impact() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    when(runMapper.selectById(FACT_RUN_ID))
        .thenReturn(run(FACT_RUN_ID, SyncRunType.FACT_REFRESH, MIRROR_RUN_ID, CONFIG_ID, "default"));
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("镜像父运行")
        .hasMessageContaining("100");
  }

  @Test
  void test_fact_child_without_parent_id_fails_lineage_validation() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    when(runMapper.selectById(FACT_RUN_ID))
        .thenReturn(run(FACT_RUN_ID, SyncRunType.FACT_REFRESH, null, CONFIG_ID, "default"));
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少镜像父运行");
  }

  @Test
  void test_non_mirror_parent_fails_lineage_validation() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    when(runMapper.selectById(FACT_RUN_ID))
        .thenReturn(run(FACT_RUN_ID, SyncRunType.FACT_REFRESH, MIRROR_RUN_ID, CONFIG_ID, "default"));
    when(runMapper.selectById(MIRROR_RUN_ID))
        .thenReturn(run(MIRROR_RUN_ID, SyncRunType.FACT_REFRESH, null, CONFIG_ID, "default"));
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("父运行不是镜像运行");
  }

  @Test
  void test_mirror_parent_config_or_source_mismatch_fails_lineage_validation() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    when(runMapper.selectById(FACT_RUN_ID))
        .thenReturn(
            run(
                FACT_RUN_ID,
                SyncRunType.FACT_REFRESH,
                MIRROR_RUN_ID,
                CONFIG_ID,
                "default"));
    when(runMapper.selectById(MIRROR_RUN_ID))
        .thenReturn(
            run(
                MIRROR_RUN_ID,
                SyncRunType.INCREMENTAL_SYNC,
                null,
                2L,
                "other"));
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("镜像父运行与事实子运行")
        .hasMessageContaining("配置或数据源不一致");
  }

  @Test
  void test_parent_with_applied_rows_but_no_table_tasks_fails_lineage_validation() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = validRunMapper(SyncRunType.INCREMENTAL_SYNC, "default");
    SyncRun parentRun = runMapper.selectById(MIRROR_RUN_ID);
    parentRun.setAppliedRows(3L);
    parentRun.setPlannedTableCount(1);
    parentRun.setCompletedTableCount(1);
    when(taskMapper.selectList(any())).thenReturn(List.of());
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    assertThatThrownBy(() -> service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("表任务链");
  }

  @Test
  void test_unrelated_successful_parent_task_returns_legitimate_empty_issue_impact() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    SyncRunMapper runMapper = validRunMapper(SyncRunType.TABLE_REFRESH, "default");
    when(taskMapper.selectList(any())).thenReturn(List.of(successfulTask("merge_requests")));
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper, runMapper);

    FactRefreshImpactScopeService.ImpactScope result =
        service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE");

    assertThat(result.fallbackRequired()).isFalse();
    assertThat(result.targets()).isEmpty();
  }

  @Test
  void test_deleted_issue_assignee_scope_remains_in_issue_fact_impact() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper);
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(501L);
    task.setRunId(MIRROR_RUN_ID);
    task.setSourceTable("issue_assignees");
    task.setStatus(SyncRunStatus.SUCCESS);
    task.setRowsApplied(1L);
    when(taskMapper.selectList(any())).thenReturn(List.of(task));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(501L)))
        .thenReturn(List.of(new FactRefreshImpactScopeService.Target(9L, 101L)));

    FactRefreshImpactScopeService.ImpactScope result =
        service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE");

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

  @Test
  void test_deleted_authoritative_note_uses_issue_scope_when_no_active_note_remains() {
    assertAuthoritativeScopeTargetsParent(
        "notes",
        "ISSUE",
        "{\"noteable_id\":404,\"noteable_type\":\"Issue\"}",
        404L,
        "ods_gitlab_issues");
  }

  @Test
  void test_deleted_authoritative_merge_request_metric_uses_parent_scope() {
    assertAuthoritativeScopeTargetsParent(
        "merge_request_metrics",
        "MERGE_REQUEST",
        "{\"merge_request_id\":505}",
        505L,
        "ods_gitlab_merge_requests");
  }

  @Test
  void test_deleted_authoritative_issue_uses_tombstone_identity() {
    assertAuthoritativeScopeTargetsParent(
        "issues",
        "ISSUE",
        "{\"id\":606}",
        606L,
        "ods_gitlab_issues");
  }

  @Test
  void test_resource_label_event_uses_gitlab_16_issue_id() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper);
    SyncRunTableTask task = successfulTask("resource_label_events");
    when(taskMapper.selectList(any())).thenReturn(List.of(task));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(501L)))
        .thenReturn(List.of(new FactRefreshImpactScopeService.Target(9L, 101L)));

    service.resolve(FACT_RUN_ID, CONFIG_ID, "default", "ISSUE");

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbcTemplate)
        .query(sqlCaptor.capture(), any(RowMapper.class), eq(501L));
    assertThat(sqlCaptor.getValue())
        .contains("event.issue_id")
        .doesNotContain("event.resource_id")
        .doesNotContain("event.resource_type");
  }

  private void assertDeletedRelationParticipates(
      String sourceTable, String factType, String expectedTargetType, String deletedPredicate) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper);
    SyncRunTableTask task = successfulTask(sourceTable);
    when(taskMapper.selectList(any())).thenReturn(List.of(task));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(501L)))
        .thenReturn(List.of(new FactRefreshImpactScopeService.Target(9L, 101L)));

    FactRefreshImpactScopeService.ImpactScope result =
        service.resolve(FACT_RUN_ID, CONFIG_ID, "default", factType);

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

  private void assertAuthoritativeScopeTargetsParent(
      String sourceTable,
      String factType,
      String lookupScopeJson,
      Long expectedSourceId,
      String expectedMirrorTable) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = mock(SyncRunTableTaskMapper.class);
    FactRefreshImpactScopeService service = service(jdbcTemplate, taskMapper);
    SyncRunTableTask task = successfulTask(sourceTable);
    task.setRowStrategy("AUTHORITATIVE");
    task.setLookupScopeJson(lookupScopeJson);
    when(taskMapper.selectList(any())).thenReturn(List.of(task));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(expectedSourceId)))
        .thenReturn(List.of(new FactRefreshImpactScopeService.Target(9L, 101L)));

    FactRefreshImpactScopeService.ImpactScope result =
        service.resolve(FACT_RUN_ID, CONFIG_ID, "default", factType);

    assertThat(result.fallbackRequired()).isFalse();
    assertThat(result.targets())
        .containsExactly(new FactRefreshImpactScopeService.Target(9L, 101L));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbcTemplate)
        .query(sqlCaptor.capture(), any(RowMapper.class), eq(expectedSourceId));
    assertThat(sqlCaptor.getValue())
        .contains(expectedMirrorTable)
        .doesNotContain("mirror_task_id")
        .doesNotContain("mirror_deleted");
  }

  private SyncRunTableTask successfulTask(String sourceTable) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(501L);
    task.setRunId(MIRROR_RUN_ID);
    task.setSourceTable(sourceTable);
    task.setStatus(SyncRunStatus.SUCCESS);
    task.setRowsApplied(1L);
    return task;
  }

  private FactRefreshImpactScopeService service(
      JdbcTemplate jdbcTemplate, SyncRunTableTaskMapper taskMapper) {
    return service(
        jdbcTemplate,
        taskMapper,
        validRunMapper(SyncRunType.INCREMENTAL_SYNC, "default"));
  }

  private FactRefreshImpactScopeService service(
      JdbcTemplate jdbcTemplate,
      SyncRunTableTaskMapper taskMapper,
      SyncRunMapper runMapper) {
    return new FactRefreshImpactScopeService(
        jdbcTemplate,
        taskMapper,
        runMapper,
        new JsonUtils(new ObjectMapper()));
  }

  private SyncRunMapper validRunMapper(SyncRunType parentRunType, String sourceInstance) {
    SyncRunMapper runMapper = mock(SyncRunMapper.class);
    when(runMapper.selectById(FACT_RUN_ID))
        .thenReturn(
            run(
                FACT_RUN_ID,
                SyncRunType.FACT_REFRESH,
                MIRROR_RUN_ID,
                CONFIG_ID,
                sourceInstance));
    when(runMapper.selectById(MIRROR_RUN_ID))
        .thenReturn(run(MIRROR_RUN_ID, parentRunType, null, CONFIG_ID, sourceInstance));
    return runMapper;
  }

  private SyncRun run(
      Long id,
      SyncRunType runType,
      Long parentRunId,
      Long configId,
      String sourceInstance) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunType(runType);
    run.setParentRunId(parentRunId);
    run.setConfigId(configId);
    run.setSourceInstance(sourceInstance);
    run.setStatus(SyncRunStatus.SUCCESS);
    run.setPlannedTableCount(0);
    run.setCompletedTableCount(0);
    run.setAppliedRows(0L);
    return run;
  }
}
