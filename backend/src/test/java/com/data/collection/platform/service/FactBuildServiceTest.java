package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FactBuildServiceTest {

  @Test
  void shouldRejectAllRebuildBeforeAnyFactBranchWhenSourcePreflightFails() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService issueFactPersistenceService = mock(IssueFactPersistenceService.class);
    IssueCustomerNameAliasService issueCustomerNameAliasService = mock(IssueCustomerNameAliasService.class);
    MergeRequestFactMapper mergeRequestFactMapper = mock(MergeRequestFactMapper.class);
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
            mergeRequestFactMapper,
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
        mergeRequestFactMapper,
        moduleDictionaryService,
        integrationTestFactBuildService,
        jdbcTemplate);
  }

  @Test
  void shouldAssociateManualFullBuildWithSyncRunBeforeSourcePreflight() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    IssueFactPersistenceService issueFactPersistenceService = mock(IssueFactPersistenceService.class);
    IssueCustomerNameAliasService issueCustomerNameAliasService = mock(IssueCustomerNameAliasService.class);
    MergeRequestFactMapper mergeRequestFactMapper = mock(MergeRequestFactMapper.class);
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
            mergeRequestFactMapper,
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
        mergeRequestFactMapper,
        moduleDictionaryService,
        integrationTestFactBuildService,
        jdbcTemplate);
  }
}
