package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
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
            sourceSchemaGuard,
            sqlQueryMonitor,
            configService,
            integrationTestFactBuildService,
            reconciliationService);

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
        .replaceAllFacts("GITLAB", "default", List.of(), List.of());
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
            sourceSchemaGuard,
            sqlQueryMonitor,
            configService,
            integrationTestFactBuildService,
            reconciliationService);

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
            sourceSchemaGuard,
            sqlQueryMonitor,
            configService,
            integrationTestFactBuildService,
            reconciliationService);

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
}
