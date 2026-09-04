package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarEntry;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarKey;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
// 事实构建服务把 GitLab 镜像表转换成 issue_fact 和 merge_request_fact，是统计和记录页的统一数据来源。
// 分类、SLA、非法标记、源行映射与搜索影子字段由专属协作者维护，本服务只编排加载与发布。
public class FactBuildService {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final String DEFAULT_SOURCE_INSTANCE = "default";
  private static final int FACT_BATCH_SIZE = 200;
  private static final List<String> RESOURCE_LABEL_EVENT_REQUIRED_COLUMNS =
      List.of("issue_id", "label_id", "action", "created_at", "mirror_deleted");

  private final JdbcTemplate jdbcTemplate;
  private final IssueFactPersistenceService issueFactPersistenceService;
  private final IssueCustomerNameAliasService issueCustomerNameAliasService;
  private final MergeRequestFactPersistenceService mergeRequestFactPersistenceService;
  private final ModuleDictionaryService moduleDictionaryService;
  private final FactBuildTaskService factBuildTaskService;
  private final GitlabSourceSchemaGuard sourceSchemaGuard;
  private final GitlabConfigService configService;
  private final IntegrationTestFactBuildService integrationTestFactBuildService;
  private final CustomerIssueMilestoneCatalogReconciliationService milestoneCatalogReconciliationService;
  private final GitlabFactSourceSqlProvider factSourceSqlProvider;
  private final GitlabFactSourceQueryExecutor factSourceQueryExecutor;
  private final IssuePhaseCalendarLoader phaseCalendarLoader;
  private final IssueFactSourceRowMapper issueRowMapper;
  private final MergeRequestFactSourceRowMapper mergeRequestRowMapper;
  private final FactSearchIndexRepairService searchIndexRepairService;

  public FactBuildService(
      JdbcTemplate jdbcTemplate,
      IssueFactPersistenceService issueFactPersistenceService,
      IssueCustomerNameAliasService issueCustomerNameAliasService,
      MergeRequestFactPersistenceService mergeRequestFactPersistenceService,
      ModuleDictionaryService moduleDictionaryService,
      FactBuildTaskService factBuildTaskService,
      GitlabSourceSchemaGuard sourceSchemaGuard,
      GitlabConfigService configService,
      IntegrationTestFactBuildService integrationTestFactBuildService,
      CustomerIssueMilestoneCatalogReconciliationService milestoneCatalogReconciliationService,
      GitlabFactSourceSqlProvider factSourceSqlProvider,
      GitlabFactSourceQueryExecutor factSourceQueryExecutor,
      IssuePhaseCalendarLoader phaseCalendarLoader,
      IssueFactSourceRowMapper issueRowMapper,
      MergeRequestFactSourceRowMapper mergeRequestRowMapper,
      FactSearchIndexRepairService searchIndexRepairService) {
    this.jdbcTemplate = jdbcTemplate;
    this.issueFactPersistenceService = issueFactPersistenceService;
    this.issueCustomerNameAliasService = issueCustomerNameAliasService;
    this.mergeRequestFactPersistenceService = mergeRequestFactPersistenceService;
    this.moduleDictionaryService = moduleDictionaryService;
    this.factBuildTaskService = factBuildTaskService;
    this.sourceSchemaGuard = sourceSchemaGuard;
    this.configService = configService;
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.milestoneCatalogReconciliationService = milestoneCatalogReconciliationService;
    this.factSourceSqlProvider = factSourceSqlProvider;
    this.factSourceQueryExecutor = factSourceQueryExecutor;
    this.phaseCalendarLoader = phaseCalendarLoader;
    this.issueRowMapper = issueRowMapper;
    this.mergeRequestRowMapper = mergeRequestRowMapper;
    this.searchIndexRepairService = searchIndexRepairService;
  }

  public FactBuildResponse rebuildAllFacts(boolean full) {
    return rebuildAllFacts(full, null);
  }

  public FactBuildResponse rebuildAllFacts(boolean full, Long configId) {
    GitlabSyncConfig config = configForId(configId);
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return factBuildTaskService.runGuarded(
        factScope("all", sourceInstance),
        full,
        () -> rebuildAllFactsInternal(full, sourceInstance, commitFactsEnabled(config)));
  }

  public FactBuildResponse rebuildAllFactsForConfig(GitlabSyncConfig config, boolean full) {
    return rebuildAllFactsForConfig(config, full, null);
  }

  /**
   * 全量重建指定数据源的全部事实层，并将构建任务关联到同步运行。
   *
   * <p>所有 ODS 源表及字段会在任一事实表写入前统一校验，避免缺表时出现部分更新。
   *
   * @param config 已保存的数据源配置
   * @param full 是否从完整 ODS 快照构建
   * @param syncRunId 所属同步运行编号；为空时创建独立事实构建任务
   * @return 全部事实层的聚合构建结果
   */
  public FactBuildResponse rebuildAllFactsForConfig(
      GitlabSyncConfig config, boolean full, Long syncRunId) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return factBuildTaskService.runGuarded(
        factScope("all", sourceInstance),
        full,
        syncRunId,
        () -> rebuildAllFactsInternal(full, sourceInstance, commitFactsEnabled(config)));
  }

  private FactBuildResponse rebuildAllFactsInternal(
      boolean full, String sourceInstance, boolean commitFactsEnabled) {
    sourceSchemaGuard.verifyAllFactSources(sourceInstance);
    if (commitFactsEnabled) {
      sourceSchemaGuard.verifyMergeRequestCommitFactSource(sourceInstance);
    }
    FactBuildResponse issue = rebuildIssueFactsInternal(full, sourceInstance);
    FactBuildResponse mergeRequest =
        rebuildMergeRequestFactsInternal(full, sourceInstance, commitFactsEnabled);
    FactBuildResponse integrationTest =
        integrationTestFactBuildService.rebuildFactsForSource(sourceInstance, full);
    return new FactBuildResponse(
        factScope("all", sourceInstance),
        full,
        issue.affectedRows() + mergeRequest.affectedRows() + integrationTest.affectedRows(),
        "事实表构建完成：议题 "
            + issue.affectedRows()
            + " 条，合并请求 "
            + mergeRequest.affectedRows()
            + " 条，集成测试 "
            + integrationTest.affectedRows()
            + " 条");
  }

  public FactBuildResponse rebuildIssueFacts(boolean full) {
    return rebuildIssueFacts(full, null);
  }

  public FactBuildResponse rebuildIssueFacts(boolean full, Long configId) {
    String sourceInstance = sourceInstanceForConfig(configId);
    return factBuildTaskService.runGuarded(
        factScope("issue", sourceInstance), full, () -> rebuildIssueFactsInternal(full, sourceInstance));
  }

  public FactBuildResponse rebuildIssueFactsForConfig(GitlabSyncConfig config, boolean full) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return factBuildTaskService.runGuarded(
        factScope("issue", sourceInstance), full, () -> rebuildIssueFactsInternal(full, sourceInstance));
  }

  public FactBuildResponse rebuildIssueFactsForQueuedTask(GitlabSyncConfig config, boolean full) {
    return rebuildIssueFactsInternal(full, GitlabSourceInstanceSupport.sourceInstanceOf(config));
  }

  /**
   * 用当前来源状态替换单个 Issue 的事实投影。
   *
   * @param sourceInstance GitLab 来源实例
   * @param projectId 项目 ID
   * @param issueIid 项目内 Issue IID
   * @return 当前来源存在时为写入结果，不存在时为删除结果
   */
  @Transactional
  public FactBuildResponse rebuildIssueFactByIid(String sourceInstance, Long projectId, Long issueIid) {
    if (projectId == null || issueIid == null) {
      throw new BizException("刷新单条议题需要项目 ID 和议题编号");
    }
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    Long rootId = findIssueRootId(normalizedSource, projectId, issueIid);
    if (rootId == null) {
      return new FactBuildResponse(
          factScope("issue", normalizedSource), false, 0, "未找到对应议题事实根");
    }
    FactBuildResponse response = rebuildIssueFactsByRootIds(normalizedSource, List.of(rootId));
    return new FactBuildResponse(
        factScope("issue", normalizedSource),
        false,
        response.affectedRows(),
        response.affectedRows() == 0 ? "对应议题事实已按当前来源删除" : "议题事实已按单条刷新");
  }

  /**
   * 原子替换指定 Issue 目标范围的事实投影。
   *
   * <p>目标来源为空时会删除旧事实和客户成员，不能退化为无操作。
   *
   * @param sourceInstance GitLab 来源实例
   * @param rootIds GitLab Issue 数据库根 ID 集合
   * @return 当前仍存在并写入的事实数量
   */
  @Transactional
  public FactBuildResponse rebuildIssueFactsByRootIds(
      String sourceInstance, List<Long> rootIds) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    List<Long> safeRootIds = distinctRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      return new FactBuildResponse(factScope("issue", normalizedSource), false, 0, "没有需要刷新的议题事实");
    }
    sourceSchemaGuard.verifyIssueFactSource(normalizedSource);
    Map<PhaseCalendarKey, PhaseCalendarEntry> calendar =
        phaseCalendarLoader.loadFactProjectionCalendar();
    ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
    Map<String, String> customerNameAliases = issueCustomerNameAliasService.loadAliases();
    List<IssueFact> facts =
        loadIssueFactsByRootIds(
            normalizedSource, safeRootIds, calendar, moduleDictionary, customerNameAliases);
    issueFactPersistenceService.replaceRootFacts(
        DEFAULT_SOURCE_SYSTEM, normalizedSource, safeRootIds, facts);
    searchIndexRepairService.refreshIssueFactSearchIndexesInBatches(facts);
    milestoneCatalogReconciliationService.reconcilePublishedFactValues();
    return new FactBuildResponse(
        factScope("issue", normalizedSource),
        false,
        facts.size(),
        "议题事实已按受影响对象刷新");
  }

  private FactBuildResponse rebuildIssueFactsInternal(boolean full, String sourceInstance) {
    sourceSchemaGuard.verifyIssueFactSource(sourceInstance);
    LocalDateTime changedSince = full ? null : getIssueFactChangedSince(sourceInstance);
    try {
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar =
          phaseCalendarLoader.loadFactProjectionCalendar();
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      Map<String, String> customerNameAliases = issueCustomerNameAliasService.loadAliases();
      List<IssueFact> facts =
          loadIssueFacts(sourceInstance, changedSince, calendar, moduleDictionary, customerNameAliases);
      if (full) {
        issueFactPersistenceService.replaceAllFacts(
            DEFAULT_SOURCE_SYSTEM, sourceInstance, facts);
        searchIndexRepairService.refreshIssueFactSearchIndexesInBatches(facts);
      } else {
        batchUpsertIssueFacts(facts);
      }
      milestoneCatalogReconciliationService.reconcilePublishedFactValues();
      return new FactBuildResponse(
          factScope("issue", sourceInstance),
          full,
          facts.size(),
          changedSince == null ? "议题事实已全量构建" : "议题事实已按增量构建");
    } catch (DataAccessException e) {
      log.warn("Failed to rebuild issue facts", e);
      throw e;
    }
  }

  public FactBuildResponse refreshCustomerIssueDelayFactsForConfig(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<IssueFact> facts = loadOpenCustomerIssueFacts(sourceInstance);
    List<IssueFact> changedFacts = new ArrayList<>();
    LocalDateTime now = currentGitlabSourceTime();
    for (IssueFact fact : facts) {
      boolean nextResponseDelayed =
          IssueFactNormalizationRules.isResponseDelayed(
              labelsOf(fact),
              fact.getRawPayload(),
              fact.getCreatedAtSource(),
              fact.getPriorityLevel(),
              now);
      boolean nextResolveDelayed =
          IssueFactNormalizationRules.isResolveDelayed(
              labelsOf(fact),
              Boolean.TRUE.equals(fact.getFixed()),
              IssueFactNormalizationRules.hasFixCaseNote(fact.getRawPayload()),
              fact.getResolveDeadlineAt(),
              now);
      if (Boolean.TRUE.equals(fact.getResponseDelayed()) == nextResponseDelayed
          && Boolean.TRUE.equals(fact.getResolveDelayed()) == nextResolveDelayed
          && Boolean.TRUE.equals(fact.getResponseOverdue()) == nextResponseDelayed) {
        continue;
      }
      fact.setResponseDelayed(nextResponseDelayed);
      fact.setResponseOverdue(nextResponseDelayed);
      fact.setResolveDelayed(nextResolveDelayed);
      changedFacts.add(fact);
    }
    batchUpsertIssueFacts(changedFacts);
    return new FactBuildResponse(
        factScope("customer-issue-delay", sourceInstance),
        false,
        changedFacts.size(),
        "客户问题延期事实已按当前时间刷新");
  }

  private List<IssueFact> loadIssueFacts(
      String sourceInstance,
      LocalDateTime changedSince,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary,
      Map<String, String> customerNameAliases) {
    boolean useResourceLabelEvents = hasResourceLabelEventSource();
    return queryIssueFacts(
        sourceInstance,
        factSourceSqlProvider.issueSourceSql(useResourceLabelEvents),
        changedSince,
        calendar,
        moduleDictionary,
        customerNameAliases);
  }

  private List<IssueFact> loadIssueFactsByRootIds(
      String sourceInstance,
      List<Long> rootIds,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary,
      Map<String, String> customerNameAliases) {
    String predicate = buildRootPredicate("i.id", rootIds);
    List<Object> args = new ArrayList<>(rootIds);
    boolean useResourceLabelEvents = hasResourceLabelEventSource();
    return queryIssueFacts(
        sourceInstance,
        factSourceSqlProvider.issueSourceSql(useResourceLabelEvents) + predicate,
        null,
        args,
        calendar,
        moduleDictionary,
        customerNameAliases);
  }

  private List<IssueFact> queryIssueFacts(
      String sourceInstance,
      String baseSql,
      LocalDateTime changedSince,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary,
      Map<String, String> customerNameAliases) {
    return queryIssueFacts(
        sourceInstance, baseSql, changedSince, List.of(), calendar, moduleDictionary, customerNameAliases);
  }

  private List<IssueFact> queryIssueFacts(
      String sourceInstance,
      String baseSql,
      LocalDateTime changedSince,
      List<Object> extraArgs,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary,
      Map<String, String> customerNameAliases) {
    return factSourceQueryExecutor.query(
        "issue-fact-source-query",
        sourceInstance,
        baseSql,
        "and coalesce(i.updated_at, i.created_at) > ?",
        changedSince,
        extraArgs,
        (rs, rowNum) ->
            issueRowMapper.mapSource(
                rs, sourceInstance, calendar, moduleDictionary, customerNameAliases));
  }

  private boolean hasResourceLabelEventSource() {
    try {
      Integer matchedColumns =
          jdbcTemplate.queryForObject(
              """
              select count(*)
                from information_schema.columns
               where table_schema = current_schema()
                 and table_name = 'ods_gitlab_resource_label_events'
                 and column_name in (?, ?, ?, ?, ?)
                 and (
                   column_name <> 'action'
                   or data_type in ('smallint', 'integer', 'bigint')
                 )
               """,
              Integer.class,
              RESOURCE_LABEL_EVENT_REQUIRED_COLUMNS.toArray());
      return matchedColumns != null && matchedColumns == RESOURCE_LABEL_EVENT_REQUIRED_COLUMNS.size();
    } catch (DataAccessException error) {
      log.debug("GitLab resource_label_events mirror table is unavailable; falling back to label_links fixed time", error);
      return false;
    }
  }

  public FactBuildResponse rebuildMergeRequestFacts(boolean full) {
    return rebuildMergeRequestFacts(full, null);
  }

  public FactBuildResponse rebuildMergeRequestFacts(boolean full, Long configId) {
    GitlabSyncConfig config = configForId(configId);
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return factBuildTaskService.runGuarded(
        factScope("merge-request", sourceInstance),
        full,
        () -> rebuildMergeRequestFactsInternal(full, sourceInstance, commitFactsEnabled(config)));
  }

  public FactBuildResponse rebuildMergeRequestFactsForConfig(GitlabSyncConfig config, boolean full) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return factBuildTaskService.runGuarded(
        factScope("merge-request", sourceInstance),
        full,
        () -> rebuildMergeRequestFactsInternal(full, sourceInstance, commitFactsEnabled(config)));
  }

  public FactBuildResponse rebuildMergeRequestFactsForQueuedTask(GitlabSyncConfig config, boolean full) {
    return rebuildMergeRequestFactsInternal(
        full,
        GitlabSourceInstanceSupport.sourceInstanceOf(config),
        commitFactsEnabled(config));
  }

  /**
   * 用当前来源状态替换单个 MR 的事实投影。
   *
   * @param sourceInstance GitLab 来源实例
   * @param projectId 目标项目 ID
   * @param mergeRequestIid 项目内 MR IID
   * @return 当前来源存在时为写入结果，不存在时为删除结果
   */
  @Transactional
  public FactBuildResponse rebuildMergeRequestFactByIid(String sourceInstance, Long projectId, Long mergeRequestIid) {
    if (projectId == null || mergeRequestIid == null) {
      throw new BizException("刷新单条合并请求需要项目 ID 和合并请求编号");
    }
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    Long rootId = findMergeRequestRootId(normalizedSource, projectId, mergeRequestIid);
    if (rootId == null) {
      return new FactBuildResponse(
          factScope("merge-request", normalizedSource), false, 0, "未找到对应合并请求事实根");
    }
    FactBuildResponse response = rebuildMergeRequestFactsByRootIds(normalizedSource, List.of(rootId));
    return new FactBuildResponse(
        factScope("merge-request", normalizedSource),
        false,
        response.affectedRows(),
        response.affectedRows() == 0
            ? "对应合并请求事实已按当前来源删除"
            : "合并请求事实已按单条刷新");
  }

  /**
   * 原子替换指定 MR 目标范围的事实投影。
   *
   * <p>目标来源为空时会删除旧事实，不能退化为无操作。
   *
   * @param sourceInstance GitLab 来源实例
   * @param rootIds GitLab MR 数据库根 ID 集合
   * @return 当前仍存在并写入的事实数量
   */
  @Transactional
  public FactBuildResponse rebuildMergeRequestFactsByRootIds(
      String sourceInstance, List<Long> rootIds) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    List<Long> safeRootIds = distinctRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      return new FactBuildResponse(factScope("merge-request", normalizedSource), false, 0, "没有需要刷新的合并请求事实");
    }
    sourceSchemaGuard.verifyMergeRequestFactSource(normalizedSource);
    boolean commitFactsEnabled = commitFactsEnabled(configService.getConfig());
    if (commitFactsEnabled) {
      sourceSchemaGuard.verifyMergeRequestCommitFactSource(normalizedSource);
    }
    ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
    List<MergeRequestFact> facts =
        factSourceQueryExecutor.query(
            "merge-request-fact-target-query",
            normalizedSource,
            factSourceSqlProvider.mergeRequestSourceSql(normalizedSource)
                + buildRootPredicate("mr.id", safeRootIds),
            "",
            null,
            new ArrayList<>(safeRootIds),
            (rs, rowNum) ->
                mergeRequestRowMapper.mapSource(rs, normalizedSource, moduleDictionary));
    List<MergeRequestCommitFact> commitFacts = commitFactsEnabled
        ? loadMergeRequestCommitFacts(normalizedSource, safeRootIds)
        : List.of();
    mergeRequestFactPersistenceService.replaceRootFacts(
        DEFAULT_SOURCE_SYSTEM, normalizedSource, safeRootIds, facts, commitFacts);
    searchIndexRepairService.refreshMergeRequestFactSearchIndexesInBatches(facts);
    return new FactBuildResponse(
        factScope("merge-request", normalizedSource),
        false,
        facts.size(),
        "合并请求事实已按受影响对象刷新");
  }

  /**
   * 在事实发布互斥与单事务边界内发布已补齐的合并请求指标。
   *
   * @param sourceInstance GitLab 数据源实例
   * @param targets 已完成指标补齐的合并请求
   * @return 成功发布时为 true；其他事实任务占用锁时为 false，调用方应保留待发布状态
   */
  public boolean publishEnrichedMergeRequestFacts(
      String sourceInstance,
      List<Long> rootIds) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    FactBuildResponse response = factBuildTaskService.runGuarded(
        factScope("merge-request", normalizedSource),
        false,
        () -> rebuildMergeRequestFactsByRootIds(normalizedSource, rootIds));
    return !FactBuildTaskService.wasSkippedBecauseBusy(response);
  }

  private FactBuildResponse rebuildMergeRequestFactsInternal(
      boolean full, String sourceInstance, boolean commitFactsEnabled) {
    sourceSchemaGuard.verifyMergeRequestFactSource(sourceInstance);
    if (commitFactsEnabled) {
      sourceSchemaGuard.verifyMergeRequestCommitFactSource(sourceInstance);
    }
    LocalDateTime changedSince = full ? null : getMergeRequestFactChangedSince(sourceInstance);
    try {
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      List<MergeRequestFact> facts = factSourceQueryExecutor.query(
          "merge-request-fact-source-query",
          sourceInstance,
          factSourceSqlProvider.mergeRequestSourceSql(sourceInstance),
          "and coalesce(mr.updated_at, mr.created_at) > ?",
          changedSince,
          (rs, rowNum) ->
              mergeRequestRowMapper.mapSource(rs, sourceInstance, moduleDictionary));
      if (full) {
        List<MergeRequestCommitFact> commitFacts = commitFactsEnabled
            ? loadMergeRequestCommitFacts(sourceInstance, List.of())
            : List.of();
        mergeRequestFactPersistenceService.replaceAllFacts(
            DEFAULT_SOURCE_SYSTEM, sourceInstance, facts, commitFacts);
        searchIndexRepairService.refreshMergeRequestFactSearchIndexesInBatches(facts);
      } else {
        List<Long> rootIds = facts.stream()
            .map(MergeRequestFact::getMergeRequestId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        if (!rootIds.isEmpty()) {
          mergeRequestFactPersistenceService.replaceRootFacts(
              DEFAULT_SOURCE_SYSTEM,
              sourceInstance,
              rootIds,
              facts,
              commitFactsEnabled
                  ? loadMergeRequestCommitFacts(sourceInstance, rootIds)
                  : List.of());
          searchIndexRepairService.refreshMergeRequestFactSearchIndexesInBatches(facts);
        }
      }
      return new FactBuildResponse(
          factScope("merge-request", sourceInstance),
          full,
          facts.size(),
          changedSince == null ? "合并请求事实已全量构建" : "合并请求事实已按增量构建");
    } catch (DataAccessException e) {
      log.warn("Failed to rebuild merge request facts", e);
      throw e;
    }
  }

  private LocalDateTime getIssueFactChangedSince(String sourceInstance) {
    int repaired = searchIndexRepairService.repairIssueFactSearchIndexes(sourceInstance);
    if (repaired > 0) {
      log.info("Repaired {} issue fact search index row(s) before incremental fact build", repaired);
    }
    return jdbcTemplate.queryForObject(
        """
            select max(ods_updated_at)
              from issue_fact
             where source_system = ?
               and source_instance = ?
            """,
        LocalDateTime.class,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance);
  }

  private LocalDateTime getMergeRequestFactChangedSince(String sourceInstance) {
    int repaired = searchIndexRepairService.repairMergeRequestFactSearchIndexes(sourceInstance);
    if (repaired > 0) {
      log.info("Repaired {} merge request fact search index row(s) before incremental fact build", repaired);
    }
    return jdbcTemplate.queryForObject(
        """
            select max(ods_updated_at)
              from merge_request_fact
             where source_system = ?
               and source_instance = ?
            """,
        LocalDateTime.class,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance);
  }

  private String sourceInstanceForConfig(Long configId) {
    return GitlabSourceInstanceSupport.sourceInstanceOf(configForId(configId));
  }

  private GitlabSyncConfig configForId(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  private boolean commitFactsEnabled(GitlabSyncConfig config) {
    return GitlabMergeRequestCommitFactCapability.isEnabled(config);
  }

  private String factScope(String scope, String sourceInstance) {
    String normalized = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    return DEFAULT_SOURCE_INSTANCE.equals(normalized) ? scope : normalized + ":" + scope;
  }

  private List<IssueFact> loadOpenCustomerIssueFacts(String sourceInstance) {
    return jdbcTemplate.query(
        """
            select f.*,
                   coalesce(current_labels.label_names, f.label_names, '') as current_label_names
              from issue_fact f
              left join ods_gitlab_issues source_issue
                on source_issue.project_id = f.project_id
               and source_issue.iid = f.issue_iid
               and coalesce(source_issue.mirror_deleted, false) = false
              left join lateral (
                select string_agg(label.title, ', ' order by label.title) as label_names
                  from ods_gitlab_label_links label_link
                  join ods_gitlab_labels label
                    on label.id = label_link.label_id
                   and coalesce(label.mirror_deleted, false) = false
                 where coalesce(label_link.mirror_deleted, false) = false
                   and label_link.target_type = 'Issue'
                   and label_link.target_id = coalesce(source_issue.id, f.issue_id)
              ) current_labels on true
             where f.source_system = ?
               and f.source_instance = ?
               and f.deleted = false
               and f.project_id = ?
               and coalesce(f.issue_state, '') <> 'closed'
               and f.created_at_source >= ?
            """,
        (resultSet, rowNumber) ->
            issueRowMapper.mapOpenCustomerIssue(resultSet, sourceInstance),
        DEFAULT_SOURCE_SYSTEM,
        sourceInstance,
        CustomerIssueScopeRules.LEGACY_CC_PRODUCT_PROJECT_ID,
        CustomerIssueScopeRules.CUSTOMER_ISSUE_START_DATE.atStartOfDay());
  }

  private LocalDateTime currentGitlabSourceTime() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  private Long findIssueRootId(String sourceInstance, Long projectId, Long issueIid) {
    List<Long> roots =
        jdbcTemplate.query(
            """
            select root_id
              from (
                select id as root_id, 0 as source_order
                  from ods_gitlab_issues
                 where project_id = ? and iid = ?
                union all
                select issue_id as root_id, 1 as source_order
                  from issue_fact
                 where source_system = ? and source_instance = ?
                   and project_id = ? and issue_iid = ?
              ) candidates
             order by source_order
             limit 1
            """,
            (resultSet, rowNum) -> resultSet.getLong("root_id"),
            projectId,
            issueIid,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance,
            projectId,
            issueIid);
    return roots.isEmpty() ? null : roots.getFirst();
  }

  private Long findMergeRequestRootId(
      String sourceInstance, Long projectId, Long mergeRequestIid) {
    List<Long> roots =
        jdbcTemplate.query(
            """
            select root_id
              from (
                select id as root_id, 0 as source_order
                  from ods_gitlab_merge_requests
                 where target_project_id = ? and iid = ?
                union all
                select merge_request_id as root_id, 1 as source_order
                  from merge_request_fact
                 where source_system = ? and source_instance = ?
                   and project_id = ? and merge_request_iid = ?
              ) candidates
             order by source_order
             limit 1
            """,
            (resultSet, rowNum) -> resultSet.getLong("root_id"),
            projectId,
            mergeRequestIid,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance,
            projectId,
            mergeRequestIid);
    return roots.isEmpty() ? null : roots.getFirst();
  }

  private List<Long> distinctRootIds(List<Long> rootIds) {
    if (rootIds == null || rootIds.isEmpty()) {
      return List.of();
    }
    return rootIds.stream()
        .filter(rootId -> rootId != null && rootId > 0L)
        .distinct()
        .sorted()
        .toList();
  }

  private String buildRootPredicate(String rootColumn, List<Long> rootIds) {
    if (rootIds == null || rootIds.isEmpty()) {
      return " and false";
    }
    return " and " + rootColumn + " in ("
        + String.join(", ", java.util.Collections.nCopies(rootIds.size(), "?")) + ")";
  }

  private List<MergeRequestCommitFact> loadMergeRequestCommitFacts(
      String sourceInstance, List<Long> rootIds) {
    List<Long> safeRootIds = distinctRootIds(rootIds);
    String rootPredicate = safeRootIds.isEmpty()
        ? ""
        : buildRootPredicate("mr.id", safeRootIds);
    return factSourceQueryExecutor.query(
        "merge-request-commit-fact-source-query",
        sourceInstance,
        factSourceSqlProvider.mergeRequestCommitSourceSql() + rootPredicate,
        "",
        null,
        new ArrayList<>(safeRootIds),
        (resultSet, rowNumber) ->
            mergeRequestRowMapper.mapCommit(resultSet, sourceInstance));
  }

  private void batchUpsertIssueFacts(List<IssueFact> facts) {
    for (List<IssueFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      issueFactPersistenceService.upsertIssueFacts(batch);
      searchIndexRepairService.refreshIssueFactSearchIndexes(batch);
    }
  }

  private List<String> labelsOf(IssueFact fact) {
    if (fact == null || !StringUtils.hasText(fact.getLabelNames())) {
      return List.of();
    }
    List<String> labels = new ArrayList<>();
    for (String part : fact.getLabelNames().split(",")) {
      if (StringUtils.hasText(part)) {
        labels.add(part.trim());
      }
    }
    return List.copyOf(labels);
  }

  private <T> List<List<T>> partition(List<T> items, int batchSize) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }
    List<List<T>> result = new ArrayList<>();
    for (int start = 0; start < items.size(); start += batchSize) {
      int end = Math.min(start + batchSize, items.size());
      result.add(items.subList(start, end));
    }
    return result;
  }

}
