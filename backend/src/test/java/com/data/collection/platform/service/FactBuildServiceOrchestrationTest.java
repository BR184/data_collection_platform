package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 修订模型下的事实构建编排测试；整轮全量预检归 SyncFactRefreshRunExecutorTest 维护。
 *
 * <p>覆盖：全量重建的校验顺序与三分支聚合、commit 能力门控、根 ID 去重排序短路、
 * busy 跳过上报。行映射口径由 FactBuildMappingRulesTest 与既有规则测试保护。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactBuildServiceOrchestrationTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private IssueFactPersistenceService issueFactPersistenceService;
  @Mock private IssueCustomerNameAliasService issueCustomerNameAliasService;
  @Mock private MergeRequestFactPersistenceService mergeRequestFactPersistenceService;
  @Mock private ModuleDictionaryService moduleDictionaryService;
  @Mock private FactBuildTaskService factBuildTaskService;
  @Mock private SyncFactPublicationStateService publicationStateService;
  @Mock private GitlabSourceSchemaGuard sourceSchemaGuard;
  @Mock private SqlQueryMonitor sqlQueryMonitor;
  @Mock private GitlabConfigService configService;
  @Mock private IntegrationTestFactBuildService integrationTestFactBuildService;
  @Mock private CustomerIssueMilestoneCatalogReconciliationService reconciliationService;

  @Mock private FactTargetPublicationService targetPublicationService;
  private com.data.collection.platform.entity.read.FactRevisionTarget issueTarget;
  private FactBuildService service;

  @BeforeEach
  void setUp() {
    issueTarget = FactRevisionFixture.target(com.data.collection.platform.entity.read.PublicationFactTable.ISSUE, "default");
    when(targetPublicationService.publishWithinCandidate(any(), any())).thenAnswer(invocation -> {
      com.data.collection.platform.service.read.BypassPublicationRequest request = invocation.getArgument(0);
      java.util.function.Function<com.data.collection.platform.entity.read.FactRevisionTarget, FactBuildResponse> body = invocation.getArgument(1);
      return body.apply(FactRevisionFixture.target(com.data.collection.platform.entity.read.PublicationFactTable.ofFactType(request.domainKey().factType()), request.domainKey().sourceInstance()));
    });
    service = newService();
    // 放行 runGuarded 三参与四参两个重载，直接执行内部编排（syncRunId 分支透传）。
    when(factBuildTaskService.runGuarded(anyString(), anyBoolean(), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Supplier<FactBuildResponse> action = invocation.getArgument(2, Supplier.class);
          return action.get();
        });
    when(factBuildTaskService
            .runGuarded(anyString(), anyBoolean(), any(), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          Supplier<FactBuildResponse> action = invocation.getArgument(3, Supplier.class);
          return action.get();
        });
    // 全量路径公共依赖：来源探测计数返回 0（label_links 模式）、空模块字典、空别名。
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(0);
    when(moduleDictionaryService.loadDictionary()).thenReturn(newModuleDictionary());
    when(issueCustomerNameAliasService.loadAliases()).thenReturn(java.util.Map.of());

    when(publicationStateService.isIssueSourceHistoryComplete(anyString())).thenReturn(true);
    when(publicationStateService.sourceSeal(anyString(), any(com.data.collection.platform.entity.FactType.class), org.mockito.ArgumentMatchers.anyLong())).thenReturn("{\"fixture\":true}");
    // 所有 jdbcTemplate.query 返回空集合（阶段日历/事实来源查询）。
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
  }

  private FactBuildService newService() {
    return new FactBuildService(
        jdbcTemplate,
        issueFactPersistenceService,
        issueCustomerNameAliasService,
        mergeRequestFactPersistenceService,
        moduleDictionaryService,
        factBuildTaskService,
        publicationStateService,
        sourceSchemaGuard,
        configService,
        targetPublicationService,
        new GitlabFactSourceSqlProvider(),
        new GitlabFactSourceQueryExecutor(jdbcTemplate, sqlQueryMonitor),
        new IssuePhaseCalendarLoader(jdbcTemplate),
        new IssueFactSourceRowMapper(),
        new MergeRequestFactSourceRowMapper(),
        new FactSearchIndexRepairService(
            jdbcTemplate,
            new IssueFactSourceRowMapper(),
            new MergeRequestFactSourceRowMapper()),
        new FactPublicationTransaction(
            new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext())),
        new com.data.collection.platform.config.GitlabMirrorProperties());
  }

  @Test
  void rebuildIssueFactsByRootIdsShouldShortCircuitOnEmptyRootIds() {
    FactBuildResponse response = service.rebuildIssueFactsByRootIds(issueTarget, List.of());

    assertThat(response.affectedRows()).isZero();
    verifyNoInteractions(sourceSchemaGuard, issueFactPersistenceService, jdbcTemplate);
  }

  @Test
  void issueFactBuildIsBlockedUntilLabelEventHistoryIsFullyVerified() {
    when(publicationStateService.isIssueSourceHistoryComplete("default")).thenReturn(false);

    assertThatThrownBy(() -> service.rebuildIssueFactsByRootIds(issueTarget, List.of(101L)))
        .hasMessageContaining("resource_label_events")
        .hasMessageContaining("全量核验");
    verifyNoInteractions(sourceSchemaGuard, issueFactPersistenceService);
  }

  @Test
  void rebuildIssueFactsByRootIdsShouldDeduplicateSortAndNormalizeRootIdsBeforeWrite() {
    // 生产 distinctRootIds 契约：过滤 null 与非正数、去重、排序。
    service.rebuildIssueFactsByRootIds(
        issueTarget, java.util.Arrays.asList(30L, null, 10L, 10L, -5L));

    verify(sourceSchemaGuard).verifyIssueFactSource("default");
    @SuppressWarnings({"unchecked", "rawtypes"})
    ArgumentCaptor<List<Long>> rootsCaptor = ArgumentCaptor.forClass((Class) List.class);
    verify(issueFactPersistenceService)
        .replaceRootFacts(eq(issueTarget), rootsCaptor.capture(), anyList());
    org.assertj.core.api.Assertions.assertThat(rootsCaptor.getValue()).containsExactly(10L, 30L);
  }





  @Test
  void publishEnrichedMergeRequestFactsShouldReportBusySkipAsFalse() {
    FactBuildResponse busyResponse =
        new FactBuildResponse("merge-request", false, 0, "已有事实构建任务正在执行，请稍后再试");
    org.mockito.Mockito.doReturn(busyResponse)
        .when(factBuildTaskService)
        .runGuarded(org.mockito.ArgumentMatchers.endsWith("merge-request"), eq(false), any());

    boolean published = service.publishEnrichedMergeRequestFacts("default", List.of(1L));

    assertThat(published).isFalse();
    verifyNoInteractions(mergeRequestFactPersistenceService);
  }

  @Test
  void publishEnrichedMergeRequestFactsShouldReportCompletedRebuildAsTrue() {
    when(configService.getConfig()).thenReturn(null);

    boolean published = service.publishEnrichedMergeRequestFacts("default", List.of(11L));

    assertThat(published).isTrue();
    verify(sourceSchemaGuard).verifyMergeRequestFactSource("default");
    @SuppressWarnings({"unchecked", "rawtypes"})
    ArgumentCaptor<List<Long>> rootsCaptor = ArgumentCaptor.forClass((Class) List.class);
    verify(mergeRequestFactPersistenceService)
        .replaceRootFacts(
            any(com.data.collection.platform.entity.read.FactRevisionTarget.class), rootsCaptor.capture(), anyList(), anyList());
    org.assertj.core.api.Assertions.assertThat(rootsCaptor.getValue()).containsExactly(11L);
  }

  /**
   * 构造空规则模块字典：私有构造器仅测试可达；空规则时归一化为恒等透传。
   */
  private ModuleDictionaryService.ModuleDictionary newModuleDictionary() {
    try {
      var constructor =
          ModuleDictionaryService.ModuleDictionary.class.getDeclaredConstructor(List.class);
      constructor.setAccessible(true);
      return constructor.newInstance(List.of());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
