package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

class FactBuildServiceTest {

  @Test
  void test_custom_merge_request_source_without_commit_tables_still_publishes_main_facts() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService issueFactPersistenceService = mock(IssueFactPersistenceService.class);
    IssueCustomerNameAliasService issueCustomerNameAliasService = mock(IssueCustomerNameAliasService.class);
    MergeRequestFactPersistenceService mergeRequestFactPersistenceService =
        mock(MergeRequestFactPersistenceService.class);
    ModuleDictionaryService moduleDictionaryService = mock(ModuleDictionaryService.class);
    FactBuildTaskService factBuildTaskService = mock(FactBuildTaskService.class);
    GitlabSourceSchemaGuard sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    SqlQueryMonitor sqlQueryMonitor = mock(SqlQueryMonitor.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    IntegrationTestFactBuildService integrationTestFactBuildService =
        mock(IntegrationTestFactBuildService.class);
    CustomerIssueMilestoneCatalogReconciliationService reconciliationService =
        mock(CustomerIssueMilestoneCatalogReconciliationService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(
        List.of(
            "merge_requests",
            "merge_request_metrics",
            "projects",
            "namespaces",
            "users",
            "merge_request_reviewers",
            "merge_request_assignees",
            "notes",
            "label_links",
            "labels",
            "resource_label_events"));

    when(factBuildTaskService.runGuarded(anyString(), eq(true), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<FactBuildResponse> action = invocation.getArgument(2, Supplier.class);
              return action.get();
            });
    when(jdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class)))
        .thenReturn(List.of());

    FactBuildService service =
        new FactBuildService(
            jdbcTemplate,
            issueFactPersistenceService,
            issueCustomerNameAliasService,
            mergeRequestFactPersistenceService,
            moduleDictionaryService,
            factBuildTaskService,
            readyPublicationStateService(),
            sourceSchemaGuard,
            configService,
            integrationTestFactBuildService,
            reconciliationService,
            new GitlabFactSourceSqlProvider(),
            new GitlabFactSourceQueryExecutor(jdbcTemplate, sqlQueryMonitor),
            new IssuePhaseCalendarLoader(jdbcTemplate),
            new IssueFactSourceRowMapper(),
            new MergeRequestFactSourceRowMapper(),
            newSearchIndexRepairService(jdbcTemplate),
            new FactPublicationTransaction(
                new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext())),
            new com.data.collection.platform.config.GitlabMirrorProperties(),
            new FactProjectionScopeResolver(jdbcTemplate),
            new FactProjectionGenerationService(jdbcTemplate));

    service.rebuildMergeRequestFactsForConfig(config, true);

    verify(sourceSchemaGuard).verifyMergeRequestFactSource("default");
    verify(sourceSchemaGuard, never()).verifyMergeRequestCommitFactSource("default");
    verify(jdbcTemplate)
        .query(
            org.mockito.ArgumentMatchers.contains("from ods_gitlab_merge_requests mr"),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    verify(jdbcTemplate, never())
        .query(
            org.mockito.ArgumentMatchers.contains("ods_gitlab_merge_request_diff_commits"),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    verify(mergeRequestFactPersistenceService)
        .deleteFactsNotInSnapshot("GITLAB", "default", List.of(), List.of());
  }

  @Test
  void shouldRejectAllRebuildBeforeAnyFactBranchWhenSourcePreflightFails() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService issueFactPersistenceService = mock(IssueFactPersistenceService.class);
    IssueCustomerNameAliasService issueCustomerNameAliasService = mock(IssueCustomerNameAliasService.class);
    MergeRequestFactPersistenceService mergeRequestFactPersistenceService =
        mock(MergeRequestFactPersistenceService.class);
    ModuleDictionaryService moduleDictionaryService = mock(ModuleDictionaryService.class);
    FactBuildTaskService factBuildTaskService = mock(FactBuildTaskService.class);
    GitlabSourceSchemaGuard sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    SqlQueryMonitor sqlQueryMonitor = mock(SqlQueryMonitor.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    IntegrationTestFactBuildService integrationTestFactBuildService = mock(IntegrationTestFactBuildService.class);
    CustomerIssueMilestoneCatalogReconciliationService reconciliationService =
        mock(CustomerIssueMilestoneCatalogReconciliationService.class);
    BizException preflightFailure = new BizException("GitLab 源结构不完整（全部事实层）");

    when(factBuildTaskService.runGuarded(anyString(), anyBoolean(), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<FactBuildResponse> action = invocation.getArgument(2, Supplier.class);
              return action.get();
            });
    doThrow(preflightFailure).when(sourceSchemaGuard).verifyAllFactSources(anyString());

    FactBuildService service =
        new FactBuildService(
            jdbcTemplate,
            issueFactPersistenceService,
            issueCustomerNameAliasService,
            mergeRequestFactPersistenceService,
            moduleDictionaryService,
            factBuildTaskService,
            readyPublicationStateService(),
            sourceSchemaGuard,
            configService,
            integrationTestFactBuildService,
            reconciliationService,
            new GitlabFactSourceSqlProvider(),
            new GitlabFactSourceQueryExecutor(jdbcTemplate, sqlQueryMonitor),
            new IssuePhaseCalendarLoader(jdbcTemplate),
            new IssueFactSourceRowMapper(),
            new MergeRequestFactSourceRowMapper(),
            newSearchIndexRepairService(jdbcTemplate),
            new FactPublicationTransaction(
                new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext())),
            new com.data.collection.platform.config.GitlabMirrorProperties(),
            new FactProjectionScopeResolver(jdbcTemplate),
            new FactProjectionGenerationService(jdbcTemplate));

    assertThatThrownBy(() -> service.rebuildAllFacts(true)).isSameAs(preflightFailure);

    verify(sourceSchemaGuard).verifyAllFactSources(anyString());
    verifyNoInteractions(
        issueFactPersistenceService,
        issueCustomerNameAliasService,
        mergeRequestFactPersistenceService,
        moduleDictionaryService,
        integrationTestFactBuildService,
        jdbcTemplate);
  }

  @Test
  void shouldAssociateManualFullBuildWithSyncRunBeforeSourcePreflight() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService issueFactPersistenceService = mock(IssueFactPersistenceService.class);
    IssueCustomerNameAliasService issueCustomerNameAliasService = mock(IssueCustomerNameAliasService.class);
    MergeRequestFactPersistenceService mergeRequestFactPersistenceService =
        mock(MergeRequestFactPersistenceService.class);
    ModuleDictionaryService moduleDictionaryService = mock(ModuleDictionaryService.class);
    FactBuildTaskService factBuildTaskService = mock(FactBuildTaskService.class);
    GitlabSourceSchemaGuard sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    SqlQueryMonitor sqlQueryMonitor = mock(SqlQueryMonitor.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    IntegrationTestFactBuildService integrationTestFactBuildService = mock(IntegrationTestFactBuildService.class);
    CustomerIssueMilestoneCatalogReconciliationService reconciliationService =
        mock(CustomerIssueMilestoneCatalogReconciliationService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    BizException preflightFailure = new BizException("GitLab 源结构不完整（全部事实层）");

    when(factBuildTaskService.runGuarded(anyString(), anyBoolean(), eq(16L), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<FactBuildResponse> action = invocation.getArgument(3, Supplier.class);
              return action.get();
            });
    doThrow(preflightFailure).when(sourceSchemaGuard).verifyAllFactSources("default");

    FactBuildService service =
        new FactBuildService(
            jdbcTemplate,
            issueFactPersistenceService,
            issueCustomerNameAliasService,
            mergeRequestFactPersistenceService,
            moduleDictionaryService,
            factBuildTaskService,
            readyPublicationStateService(),
            sourceSchemaGuard,
            configService,
            integrationTestFactBuildService,
            reconciliationService,
            new GitlabFactSourceSqlProvider(),
            new GitlabFactSourceQueryExecutor(jdbcTemplate, sqlQueryMonitor),
            new IssuePhaseCalendarLoader(jdbcTemplate),
            new IssueFactSourceRowMapper(),
            new MergeRequestFactSourceRowMapper(),
            newSearchIndexRepairService(jdbcTemplate),
            new FactPublicationTransaction(
                new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext())),
            new com.data.collection.platform.config.GitlabMirrorProperties(),
            new FactProjectionScopeResolver(jdbcTemplate),
            new FactProjectionGenerationService(jdbcTemplate));

    assertThatThrownBy(() -> service.rebuildAllFactsForConfig(config, true, 16L))
        .isSameAs(preflightFailure);

    verify(factBuildTaskService).runGuarded(anyString(), eq(true), eq(16L), any());
    verify(sourceSchemaGuard).verifyAllFactSources("default");
    verifyNoInteractions(
        issueFactPersistenceService,
        issueCustomerNameAliasService,
        mergeRequestFactPersistenceService,
        moduleDictionaryService,
        integrationTestFactBuildService,
        jdbcTemplate);
  }

  @Test
  void shouldWriteOnlyDelayFlagsWhenRefreshingCustomerIssueDelayFacts() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService issueFactPersistenceService = mock(IssueFactPersistenceService.class);
    IssueCustomerNameAliasService issueCustomerNameAliasService = mock(IssueCustomerNameAliasService.class);
    MergeRequestFactPersistenceService mergeRequestFactPersistenceService =
        mock(MergeRequestFactPersistenceService.class);
    ModuleDictionaryService moduleDictionaryService = mock(ModuleDictionaryService.class);
    FactBuildTaskService factBuildTaskService = mock(FactBuildTaskService.class);
    GitlabSourceSchemaGuard sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    SqlQueryMonitor sqlQueryMonitor = mock(SqlQueryMonitor.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    IntegrationTestFactBuildService integrationTestFactBuildService =
        mock(IntegrationTestFactBuildService.class);
    CustomerIssueMilestoneCatalogReconciliationService reconciliationService =
        mock(CustomerIssueMilestoneCatalogReconciliationService.class);
    FactSearchIndexRepairService searchIndexRepairService =
        mock(FactSearchIndexRepairService.class);

    IssueFact staleFact = new IssueFact();
    staleFact.setId(42L);
    staleFact.setRawPayload("");
    staleFact.setCreatedAtSource(LocalDateTime.now(ZoneOffset.UTC).minusDays(100));
    staleFact.setPriorityLevel("P3");
    when(jdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<IssueFact>>any(),
            any(Object[].class)))
        .thenReturn(List.of(staleFact));

    FactBuildService service =
        new FactBuildService(
            jdbcTemplate,
            issueFactPersistenceService,
            issueCustomerNameAliasService,
            mergeRequestFactPersistenceService,
            moduleDictionaryService,
            factBuildTaskService,
            readyPublicationStateService(),
            sourceSchemaGuard,
            configService,
            integrationTestFactBuildService,
            reconciliationService,
            new GitlabFactSourceSqlProvider(),
            new GitlabFactSourceQueryExecutor(jdbcTemplate, sqlQueryMonitor),
            new IssuePhaseCalendarLoader(jdbcTemplate),
            new IssueFactSourceRowMapper(),
            new MergeRequestFactSourceRowMapper(),
            searchIndexRepairService,
            new FactPublicationTransaction(
                new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext())),
            new com.data.collection.platform.config.GitlabMirrorProperties(),
            new FactProjectionScopeResolver(jdbcTemplate),
            new FactProjectionGenerationService(jdbcTemplate));

    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("default");
    when(jdbcTemplate.batchUpdate(anyString(), any(), anyInt(), any()))
        .thenReturn(new int[][] {{1}});
    service.refreshCustomerIssueDelayFactsForConfig(config);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), any(), anyInt(), any());
    assertThat(sqlCaptor.getValue().replaceAll("\\s+", " ").trim().toLowerCase())
        .isEqualTo(
            "update issue_fact set is_response_delayed = ?, response_overdue = ?,"
                + " is_resolve_delayed = ?, updated_at = current_timestamp where id = ?");
    verifyNoInteractions(issueFactPersistenceService, searchIndexRepairService);
  }

  /**
   * 全量构建必须装载"全部"提交关系。
   *
   * <p>提交事实增强启用时，全量构建原先把空根集合传给按根装载器，而按根口径把它当成空结果，导致全量构建
   * 静默发布零条提交关系并让随后的快照清理删掉既有关系。本测试锁定全量构建发出的是未收窄的提交查询。
   */
  @Test
  void test_full_merge_request_build_loads_all_commit_relations_without_root_scope() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceSchemaGuard sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    MergeRequestFactPersistenceService mergeRequestFactPersistenceService =
        mock(MergeRequestFactPersistenceService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    when(configService.getConfig()).thenReturn(config);
    when(jdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class)))
        .thenReturn(List.of());

    FactBuildService service =
        buildService(jdbcTemplate, sourceSchemaGuard, configService, mergeRequestFactPersistenceService);
    service.rebuildMergeRequestFactsForConfig(config, true);

    verify(sourceSchemaGuard).verifyMergeRequestCommitFactSource("default");
    List<String> commitQueries =
        capturedQueries(jdbcTemplate).stream()
            .filter(sql -> sql.contains("ods_gitlab_merge_request_diff_commits"))
            .toList();
    assertThat(commitQueries)
        .as("全量构建必须装载提交关系，且只装载一次")
        .hasSize(1);
    assertThat(commitQueries.getFirst())
        .as("全量构建装载全部提交关系，不得按根收窄")
        .doesNotContain("target_roots");
  }

  /** 按根构建必须把根集合下推到提交关系查询。 */
  @Test
  void test_root_scoped_merge_request_build_narrows_commit_relations_to_the_root_set() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabSourceSchemaGuard sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    GitlabConfigService configService = mock(GitlabConfigService.class);
    MergeRequestFactPersistenceService mergeRequestFactPersistenceService =
        mock(MergeRequestFactPersistenceService.class);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    when(configService.getConfig()).thenReturn(config);
    when(jdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class)))
        .thenReturn(List.of());

    FactBuildService service =
        buildService(jdbcTemplate, sourceSchemaGuard, configService, mergeRequestFactPersistenceService);
    service.rebuildMergeRequestFactsByRootIds("default", List.of(7L));

    verify(sourceSchemaGuard).verifyMergeRequestCommitFactSource("default");
    List<String> commitQueries =
        capturedQueries(jdbcTemplate).stream()
            .filter(sql -> sql.contains("ods_gitlab_merge_request_diff_commits"))
            .toList();
    assertThat(commitQueries).as("按根构建必须装载提交关系").hasSize(1);
    assertThat(commitQueries.getFirst())
        .as("按根构建必须把根集合下推到提交关系查询")
        .contains("target_roots");
  }

  private List<String> capturedQueries(JdbcTemplate jdbcTemplate) {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, atLeastOnce())
        .query(
            sqlCaptor.capture(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    return sqlCaptor.getAllValues();
  }

  private FactBuildService buildService(
      JdbcTemplate jdbcTemplate,
      GitlabSourceSchemaGuard sourceSchemaGuard,
      GitlabConfigService configService,
      MergeRequestFactPersistenceService mergeRequestFactPersistenceService) {
    FactBuildTaskService factBuildTaskService = mock(FactBuildTaskService.class);
    when(factBuildTaskService.runGuarded(anyString(), anyBoolean(), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<FactBuildResponse> action = invocation.getArgument(2, Supplier.class);
              return action.get();
            });
    return new FactBuildService(
        jdbcTemplate,
        mock(IssueFactPersistenceService.class),
        mock(IssueCustomerNameAliasService.class),
        mergeRequestFactPersistenceService,
        mock(ModuleDictionaryService.class),
        factBuildTaskService,
        readyPublicationStateService(),
        sourceSchemaGuard,
        configService,
        mock(IntegrationTestFactBuildService.class),
        mock(CustomerIssueMilestoneCatalogReconciliationService.class),
        new GitlabFactSourceSqlProvider(),
        new GitlabFactSourceQueryExecutor(jdbcTemplate, mock(SqlQueryMonitor.class)),
        new IssuePhaseCalendarLoader(jdbcTemplate),
        new IssueFactSourceRowMapper(),
        new MergeRequestFactSourceRowMapper(),
        newSearchIndexRepairService(jdbcTemplate),
        new FactPublicationTransaction(
            new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext())),
        new com.data.collection.platform.config.GitlabMirrorProperties(),
        new FactProjectionScopeResolver(jdbcTemplate),
        new FactProjectionGenerationService(jdbcTemplate));
  }

  private SyncFactPublicationStateService readyPublicationStateService() {
    SyncFactPublicationStateService service = mock(SyncFactPublicationStateService.class);
    when(service.isIssueSourceHistoryComplete(anyString())).thenReturn(true);
    return service;
  }

  private FactSearchIndexRepairService newSearchIndexRepairService(
      JdbcTemplate jdbcTemplate) {
    return new FactSearchIndexRepairService(
        jdbcTemplate,
        new IssueFactSourceRowMapper(),
        new MergeRequestFactSourceRowMapper());
  }
}
