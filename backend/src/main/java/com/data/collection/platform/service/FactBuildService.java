package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
// 这里集中处理分类、SLA、非法标记和搜索影子字段，避免各查询接口重复推导事实口径。
public class FactBuildService {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final String DEFAULT_SOURCE_INSTANCE = "default";
  private static final String MIRROR_INGEST_CHANNEL = "MIRROR";
  private static final int FACT_BATCH_SIZE = 200;
  private static final int SEARCH_INDEX_REPAIR_LIMIT = 1000;
  private static final List<String> RESOURCE_LABEL_EVENT_REQUIRED_COLUMNS =
      List.of("issue_id", "label_id", "action", "created_at", "mirror_deleted");

  private final JdbcTemplate jdbcTemplate;
  private final IssueFactPersistenceService issueFactPersistenceService;
  private final IssueCustomerNameAliasService issueCustomerNameAliasService;
  private final MergeRequestFactPersistenceService mergeRequestFactPersistenceService;
  private final ModuleDictionaryService moduleDictionaryService;
  private final FactBuildTaskService factBuildTaskService;
  private final GitlabSourceSchemaGuard sourceSchemaGuard;
  private final SqlQueryMonitor sqlQueryMonitor;
  private final GitlabConfigService configService;
  private final IntegrationTestFactBuildService integrationTestFactBuildService;
  private final CustomerIssueMilestoneCatalogReconciliationService milestoneCatalogReconciliationService;
  private final GitlabFactSourceSqlProvider factSourceSqlProvider;
  private final GitlabFactSourceQueryExecutor factSourceQueryExecutor;

  public FactBuildService(
      JdbcTemplate jdbcTemplate,
      IssueFactPersistenceService issueFactPersistenceService,
      IssueCustomerNameAliasService issueCustomerNameAliasService,
      MergeRequestFactPersistenceService mergeRequestFactPersistenceService,
      ModuleDictionaryService moduleDictionaryService,
      FactBuildTaskService factBuildTaskService,
      GitlabSourceSchemaGuard sourceSchemaGuard,
      SqlQueryMonitor sqlQueryMonitor,
      GitlabConfigService configService,
      IntegrationTestFactBuildService integrationTestFactBuildService,
      CustomerIssueMilestoneCatalogReconciliationService milestoneCatalogReconciliationService) {
    this.jdbcTemplate = jdbcTemplate;
    this.issueFactPersistenceService = issueFactPersistenceService;
    this.issueCustomerNameAliasService = issueCustomerNameAliasService;
    this.mergeRequestFactPersistenceService = mergeRequestFactPersistenceService;
    this.moduleDictionaryService = moduleDictionaryService;
    this.factBuildTaskService = factBuildTaskService;
    this.sourceSchemaGuard = sourceSchemaGuard;
    this.sqlQueryMonitor = sqlQueryMonitor;
    this.configService = configService;
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.milestoneCatalogReconciliationService = milestoneCatalogReconciliationService;
    this.factSourceSqlProvider = new GitlabFactSourceSqlProvider();
    this.factSourceQueryExecutor = new GitlabFactSourceQueryExecutor(jdbcTemplate, sqlQueryMonitor);
  }

  public FactBuildResponse rebuildAllFacts(boolean full) {
    return rebuildAllFacts(full, null);
  }

  public FactBuildResponse rebuildAllFacts(boolean full, Long configId) {
    String sourceInstance = sourceInstanceForConfig(configId);
    return factBuildTaskService.runGuarded(
        factScope("all", sourceInstance), full, () -> rebuildAllFactsInternal(full, sourceInstance));
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
        () -> rebuildAllFactsInternal(full, sourceInstance));
  }

  private FactBuildResponse rebuildAllFactsInternal(boolean full, String sourceInstance) {
    sourceSchemaGuard.verifyAllFactSources(sourceInstance);
    FactBuildResponse issue = rebuildIssueFactsInternal(full, sourceInstance);
    FactBuildResponse mergeRequest = rebuildMergeRequestFactsInternal(full, sourceInstance);
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
    Map<PhaseCalendarKey, PhaseCalendarEntry> calendar = loadPhaseCalendar();
    ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
    Map<String, String> customerNameAliases = issueCustomerNameAliasService.loadAliases();
    List<IssueFact> facts =
        loadIssueFactsByRootIds(
            normalizedSource, safeRootIds, calendar, moduleDictionary, customerNameAliases);
    issueFactPersistenceService.replaceRootFacts(
        DEFAULT_SOURCE_SYSTEM, normalizedSource, safeRootIds, facts);
    refreshIssueFactSearchIndexesInBatches(facts);
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
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar = loadPhaseCalendar();
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      Map<String, String> customerNameAliases = issueCustomerNameAliasService.loadAliases();
      List<IssueFact> facts =
          loadIssueFacts(sourceInstance, changedSince, calendar, moduleDictionary, customerNameAliases);
      if (full) {
        issueFactPersistenceService.replaceAllFacts(
            DEFAULT_SOURCE_SYSTEM, sourceInstance, facts);
        refreshIssueFactSearchIndexesInBatches(facts);
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
    try {
      return queryIssueFacts(
          sourceInstance,
          factSourceSqlProvider.issueSourceSql(useResourceLabelEvents),
          changedSince,
          calendar,
          moduleDictionary,
          customerNameAliases);
    } catch (DataAccessException error) {
      if (!isMilestoneQueryFallbackAllowed(error)) {
        throw error;
      }
      log.warn("Issue fact build fallback activated because milestone join is unavailable", error);
      return queryIssueFacts(
          sourceInstance,
          factSourceSqlProvider.issueSourceSqlFallback(useResourceLabelEvents),
          changedSince,
          calendar,
          moduleDictionary,
          customerNameAliases);
    }
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
    try {
      return queryIssueFacts(
          sourceInstance,
          factSourceSqlProvider.issueSourceSql(useResourceLabelEvents) + predicate,
          null,
          args,
          calendar,
          moduleDictionary,
          customerNameAliases);
    } catch (DataAccessException error) {
      if (!isMilestoneQueryFallbackAllowed(error)) {
        throw error;
      }
      log.warn("Targeted issue fact build fallback activated because milestone join is unavailable", error);
      return queryIssueFacts(
          sourceInstance,
          factSourceSqlProvider.issueSourceSqlFallback(useResourceLabelEvents) + predicate,
          null,
          args,
          calendar,
          moduleDictionary,
          customerNameAliases);
    }
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
            mapIssueFact(rs, sourceInstance, calendar, moduleDictionary, customerNameAliases));
  }

  private boolean isMilestoneQueryFallbackAllowed(DataAccessException error) {
    String message =
        error.getMostSpecificCause() == null
            ? error.getMessage()
            : error.getMostSpecificCause().getMessage();
    if (!StringUtils.hasText(message)) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return normalized.contains("ods_gitlab_milestones")
        || normalized.contains("milestone_id")
        || normalized.contains("milestone");
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
    String sourceInstance = sourceInstanceForConfig(configId);
    return factBuildTaskService.runGuarded(
        factScope("merge-request", sourceInstance), full, () -> rebuildMergeRequestFactsInternal(full, sourceInstance));
  }

  public FactBuildResponse rebuildMergeRequestFactsForConfig(GitlabSyncConfig config, boolean full) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return factBuildTaskService.runGuarded(
        factScope("merge-request", sourceInstance), full, () -> rebuildMergeRequestFactsInternal(full, sourceInstance));
  }

  public FactBuildResponse rebuildMergeRequestFactsForQueuedTask(GitlabSyncConfig config, boolean full) {
    return rebuildMergeRequestFactsInternal(full, GitlabSourceInstanceSupport.sourceInstanceOf(config));
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
            (rs, rowNum) -> mapMergeRequestFact(rs, rowNum, normalizedSource, moduleDictionary));
    mergeRequestFactPersistenceService.replaceRootFacts(
        DEFAULT_SOURCE_SYSTEM, normalizedSource, safeRootIds, facts);
    refreshMergeRequestFactSearchIndexesInBatches(facts);
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

  private FactBuildResponse rebuildMergeRequestFactsInternal(boolean full, String sourceInstance) {
    sourceSchemaGuard.verifyMergeRequestFactSource(sourceInstance);
    LocalDateTime changedSince = full ? null : getMergeRequestFactChangedSince(sourceInstance);
    try {
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      List<MergeRequestFact> facts = factSourceQueryExecutor.query(
          "merge-request-fact-source-query",
          sourceInstance,
          factSourceSqlProvider.mergeRequestSourceSql(sourceInstance),
          "and coalesce(mr.updated_at, mr.created_at) > ?",
          changedSince,
          (rs, rowNum) -> mapMergeRequestFact(rs, rowNum, sourceInstance, moduleDictionary));
      if (full) {
        mergeRequestFactPersistenceService.replaceAllFacts(
            DEFAULT_SOURCE_SYSTEM, sourceInstance, facts);
        refreshMergeRequestFactSearchIndexesInBatches(facts);
      } else {
        batchUpsertMergeRequestFacts(facts);
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
    int repaired = repairIssueFactSearchIndexes(sourceInstance);
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
    int repaired = repairMergeRequestFactSearchIndexes(sourceInstance);
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

  private int repairIssueFactSearchIndexes(String sourceInstance) {
    int repaired = 0;
    while (repaired < SEARCH_INDEX_REPAIR_LIMIT) {
      int batchLimit = Math.min(FACT_BATCH_SIZE, SEARCH_INDEX_REPAIR_LIMIT - repaired);
      List<IssueFact> batch = loadIssueFactsMissingSearchIndexes(sourceInstance, batchLimit);
      if (batch.isEmpty()) {
        return repaired;
      }
      refreshIssueFactSearchIndexes(batch);
      repaired += batch.size();
      if (batch.size() < batchLimit) {
        return repaired;
      }
    }
    return repaired;
  }

  private List<IssueFact> loadIssueFactsMissingSearchIndexes(String sourceInstance, int limit) {
    return jdbcTemplate.query(
        """
            select source_system,
                   source_instance,
                   project_id,
                   issue_id,
                   issue_iid,
                   title,
                   project_name,
                   module_names,
                   customer_names,
                   testing_phase,
                   system_test_label,
                   label_names,
                   reason_category,
                   illegal_reason,
                   author_name,
                   handler_name,
                   assignee_name,
                   bug_status,
                   category,
                   milestone_title
              from issue_fact
             where source_system = ?
               and source_instance = ?
               and deleted = false
               and (
                    search_text is null
                 or search_compact is null
                 or search_spell is null
                 or search_initials is null
                 or primary_phase_label is null
                 or phase_filter_value is null
               )
             order by updated_at_source desc nulls last, issue_id desc
             limit ?
            """,
        (rs, rowNum) -> {
          IssueFact fact = new IssueFact();
          fact.setSourceSystem(defaultText(rs.getString("source_system"), DEFAULT_SOURCE_SYSTEM));
          fact.setSourceInstance(defaultText(rs.getString("source_instance"), sourceInstance));
          fact.setProjectId(rs.getLong("project_id"));
          fact.setIssueId(rs.getLong("issue_id"));
          fact.setIssueIid(nullableLong(rs, "issue_iid"));
          fact.setTitle(defaultText(rs.getString("title")));
          fact.setProjectName(defaultText(rs.getString("project_name")));
          fact.setModuleNames(defaultText(rs.getString("module_names")));
          fact.setCustomerNames(defaultText(rs.getString("customer_names")));
          fact.setTestingPhase(defaultText(rs.getString("testing_phase")));
          fact.setSystemTestLabel(defaultText(rs.getString("system_test_label")));
          fact.setLabelNames(defaultText(rs.getString("label_names")));
          fact.setReasonCategory(defaultText(rs.getString("reason_category")));
          fact.setIllegalReason(defaultText(rs.getString("illegal_reason")));
          fact.setAuthorName(defaultText(rs.getString("author_name")));
          fact.setHandlerName(defaultText(rs.getString("handler_name")));
          fact.setAssigneeName(defaultText(rs.getString("assignee_name")));
          fact.setBugStatus(defaultText(rs.getString("bug_status")));
          fact.setCategory(defaultText(rs.getString("category")));
          fact.setMilestoneTitle(defaultText(rs.getString("milestone_title")));
          return fact;
        },
        DEFAULT_SOURCE_SYSTEM,
        sourceInstance,
        limit);
  }

  private int repairMergeRequestFactSearchIndexes(String sourceInstance) {
    int repaired = 0;
    while (repaired < SEARCH_INDEX_REPAIR_LIMIT) {
      int batchLimit = Math.min(FACT_BATCH_SIZE, SEARCH_INDEX_REPAIR_LIMIT - repaired);
      List<MergeRequestFact> batch = loadMergeRequestFactsMissingSearchIndexes(sourceInstance, batchLimit);
      if (batch.isEmpty()) {
        return repaired;
      }
      refreshMergeRequestFactSearchIndexes(batch);
      repaired += batch.size();
      if (batch.size() < batchLimit) {
        return repaired;
      }
    }
    return repaired;
  }

  private List<MergeRequestFact> loadMergeRequestFactsMissingSearchIndexes(String sourceInstance, int limit) {
    return jdbcTemplate.query(
        """
            select source_system,
                   source_instance,
                   project_id,
                   merge_request_id,
                   title,
                   author_name,
                   owner_name,
                   project_name,
                   repository_name,
                   module_name,
                   target_branch,
                   merge_user_name
              from merge_request_fact
             where source_system = ?
               and source_instance = ?
               and deleted = false
               and (
                    search_text is null
                 or search_compact is null
                 or search_spell is null
                 or search_initials is null
                 or owner_search_text is null
                 or owner_search_compact is null
                 or owner_search_spell is null
                 or owner_search_initials is null
               )
             order by merged_at_source desc nulls last, merge_request_id desc
             limit ?
            """,
        (rs, rowNum) -> {
          MergeRequestFact fact = new MergeRequestFact();
          fact.setSourceSystem(defaultText(rs.getString("source_system"), DEFAULT_SOURCE_SYSTEM));
          fact.setSourceInstance(defaultText(rs.getString("source_instance"), sourceInstance));
          fact.setProjectId(rs.getLong("project_id"));
          fact.setMergeRequestId(rs.getLong("merge_request_id"));
          fact.setTitle(defaultText(rs.getString("title")));
          fact.setAuthorName(defaultText(rs.getString("author_name")));
          fact.setOwnerName(defaultText(rs.getString("owner_name")));
          fact.setProjectName(defaultText(rs.getString("project_name")));
          fact.setRepositoryName(defaultText(rs.getString("repository_name")));
          fact.setModuleName(defaultText(rs.getString("module_name")));
          fact.setTargetBranch(defaultText(rs.getString("target_branch")));
          fact.setMergeUserName(defaultText(rs.getString("merge_user_name")));
          return fact;
        },
        DEFAULT_SOURCE_SYSTEM,
        sourceInstance,
        limit);
  }

  private String sourceInstanceForConfig(Long configId) {
    return GitlabSourceInstanceSupport.sourceInstanceOf(
        configId == null ? configService.getConfig() : configService.getConfigById(configId));
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
        (rs, rowNum) -> {
          IssueFact fact = new IssueFact();
          fact.setSourceSystem(defaultText(rs.getString("source_system"), DEFAULT_SOURCE_SYSTEM));
          fact.setSourceInstance(defaultText(rs.getString("source_instance"), sourceInstance));
          fact.setIngestChannel(defaultText(rs.getString("ingest_channel")));
          fact.setSourceSummary(defaultText(rs.getString("source_summary")));
          fact.setRawPayload(defaultText(rs.getString("raw_payload"), ""));
          fact.setProjectId(rs.getLong("project_id"));
          fact.setProjectName(defaultText(rs.getString("project_name")));
          fact.setIssueId(rs.getLong("issue_id"));
          fact.setIssueIid(nullableLong(rs, "issue_iid"));
          fact.setTitle(defaultText(rs.getString("title")));
          fact.setIssueState(defaultText(rs.getString("issue_state")));
          fact.setIssueType(defaultText(rs.getString("issue_type")));
          fact.setMilestoneTitle(defaultText(rs.getString("milestone_title")));
          fact.setAuthorName(defaultText(rs.getString("author_name")));
          fact.setHandlerName(defaultText(rs.getString("handler_name")));
          fact.setAssigneeName(defaultText(rs.getString("assignee_name")));
          fact.setCreatedAtSource(toLocalDateTime(rs.getTimestamp("created_at_source")));
          fact.setUpdatedAtSource(toLocalDateTime(rs.getTimestamp("updated_at_source")));
          fact.setOdsUpdatedAt(toLocalDateTime(rs.getTimestamp("ods_updated_at")));
          fact.setClosedAtSource(toLocalDateTime(rs.getTimestamp("closed_at_source")));
          fact.setModuleName(defaultText(rs.getString("module_name"), null));
          fact.setPrimaryModuleName(defaultText(rs.getString("primary_module_name"), null));
          fact.setModuleNames(defaultText(rs.getString("module_names")));
          fact.setCustomerNames(defaultText(rs.getString("customer_names")));
          fact.setFunctionName(defaultText(rs.getString("function_name")));
          fact.setTestingPhase(defaultText(rs.getString("testing_phase")));
          fact.setSeverityLevel(defaultText(rs.getString("severity_level")));
          fact.setSeverityAlias(defaultText(rs.getString("severity_alias")));
          fact.setPriorityLevel(defaultText(rs.getString("priority_level")));
          fact.setUrgency(defaultText(rs.getString("urgency")));
          fact.setBugStatus(defaultText(rs.getString("bug_status")));
          fact.setCategory(defaultText(rs.getString("category")));
          fact.setReasonCategory(defaultText(rs.getString("reason_category")));
          fact.setSystemTestLabel(defaultText(rs.getString("system_test_label")));
          fact.setLabelNames(defaultText(rs.getString("current_label_names")));
          fact.setExcluded(rs.getBoolean("is_excluded"));
          fact.setExclusionReason(defaultText(rs.getString("exclusion_reason")));
          fact.setFixed(rs.getBoolean("is_fixed"));
          fact.setDelayIssue(rs.getBoolean("delay_issue"));
          fact.setDelayReason(defaultText(rs.getString("delay_reason")));
          fact.setDelayCause(defaultText(rs.getString("delay_cause")));
          fact.setRegression(rs.getBoolean("is_regression"));
          fact.setCrash(rs.getBoolean("is_crash"));
          fact.setLevel1Other(rs.getBoolean("is_level1_other"));
          fact.setIllegal(rs.getBoolean("is_illegal"));
          fact.setIllegalReason(defaultText(rs.getString("illegal_reason")));
          fact.setIllegalReasons(defaultText(rs.getString("illegal_reasons")));
          fact.setHasResponse(rs.getBoolean("has_response"));
          fact.setResearchTemplateTime(toLocalDateTime(rs.getTimestamp("research_template_time")));
          fact.setResponseOverdue(rs.getBoolean("response_overdue"));
          fact.setResponseDelayed(rs.getBoolean("is_response_delayed"));
          fact.setResolveSlaDays(rs.getInt("resolve_sla_days"));
          fact.setResolveDeadlineAt(toLocalDateTime(rs.getTimestamp("resolve_deadline_at")));
          fact.setPlannedResolutionAt(toLocalDateTime(rs.getTimestamp("planned_resolution_at")));
          fact.setPlannedResolutionText(defaultText(rs.getString("planned_resolution_text")));
          fact.setPlannedMergeVersionBranch(
              defaultText(rs.getString("planned_merge_version_branch")));
          fact.setFixedLabelTime(toLocalDateTime(rs.getTimestamp("fixed_label_time")));
          fact.setResolveDelayed(rs.getBoolean("is_resolve_delayed"));
          fact.setLegacy(rs.getBoolean("is_legacy"));
          fact.setDeleted(false);
          return fact;
        },
        DEFAULT_SOURCE_SYSTEM,
        sourceInstance,
        CustomerIssueScopeRules.LEGACY_CC_PRODUCT_PROJECT_ID,
        CustomerIssueScopeRules.CUSTOMER_ISSUE_START_DATE.atStartOfDay());
  }

  private Map<PhaseCalendarKey, PhaseCalendarEntry> loadPhaseCalendar() {
    List<PhaseCalendarEntry> entries = jdbcTemplate.query(
        """
            select c.project_id,
                   m.source_value as testing_phase,
                   m.active_from as phase_start_at,
                   m.active_until as phase_end_at,
                   m.enabled
              from issue_scope_catalogs c
              join issue_scope_groups g
                on g.catalog_id = c.id
               and g.enabled = true
              join issue_scope_members m
                on m.catalog_id = c.id
               and m.group_id = g.id
               and m.enabled = true
             where c.dimension = 'TESTING_PHASE'
               and c.enabled = true
            """,
        (rs, rowNum) -> new PhaseCalendarEntry(
            rs.getLong("project_id"),
            defaultText(rs.getString("testing_phase"), null),
            toLocalDateTime(rs.getTimestamp("phase_start_at")),
            toLocalDateTime(rs.getTimestamp("phase_end_at")),
            rs.getBoolean("enabled")));
    Map<PhaseCalendarKey, PhaseCalendarEntry> result = new LinkedHashMap<>();
    entries.stream()
        .sorted(Comparator.comparing(PhaseCalendarEntry::phaseStartAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
        .forEach(entry -> result.putIfAbsent(new PhaseCalendarKey(entry.projectId(), normalizeKey(entry.testingPhase())), entry));
    return result;
  }

  private IssueFact mapIssueFact(
      ResultSet rs,
      String sourceInstance,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary,
      Map<String, String> customerNameAliases) throws SQLException {
    List<String> labels = readTextArray(rs.getArray("label_titles"));
    String title = defaultText(rs.getString("title"));
    String description = defaultText(rs.getString("description"));
    String notesText = defaultText(rs.getString("notes_text"), "");
    boolean closed = isClosed(rs);
    LocalDateTime createdAt = toLocalDateTime(rs.getTimestamp("created_at"));
    long projectId = rs.getLong("project_id");
    String projectName = rs.getString("project_name");
    boolean customerProject =
        CustomerIssueScopeRules.isCustomerProject(projectId, projectName);
    String testingPhase = IssueFactNormalizationRules.normalizeTestingPhase(labels);
    List<String> moduleNames =
        moduleDictionary.normalizeIssueModules(
            projectId,
            IssueFactNormalizationRules.normalizeModuleNames(labels));
    String severityLevel = IssueFactNormalizationRules.normalizeSeverityLevel(labels);
    String priorityLevel = IssueFactNormalizationRules.normalizePriorityLevel(labels);
    int resolveSlaDays = IssueFactNormalizationRules.resolveSlaDays(notesText);
    LocalDateTime resolveDeadlineAt = IssueFactNormalizationRules.resolveDeadline(createdAt, notesText);
    PhaseCalendarEntry phaseCalendar = calendar.get(new PhaseCalendarKey(projectId, normalizeKey(testingPhase)));
    boolean customerIssue = isCustomerIssueIssueFact(labels, projectId, projectName, createdAt);
    boolean openCustomerIssue = customerIssue && !closed;
    LocalDateTime now = currentGitlabSourceTime();
    List<String> customerNames =
        customerProject
            ? IssueCustomerNameParser.parse(description, title, customerNameAliases)
            : List.of();
    IssueResponseTemplate responseTemplate =
        customerProject ? IssueResponseTemplateParser.parse(notesText) : IssueResponseTemplate.empty();

    IssueFact fact = new IssueFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(sourceInstance);
    fact.setIngestChannel(MIRROR_INGEST_CHANNEL);
    fact.setSourceSummary("GitLab issue 镜像聚合");
    fact.setRawPayload(notesText);
    fact.setProjectId(projectId);
    fact.setProjectName(defaultText(projectName));
    fact.setIssueId(rs.getLong("issue_id"));
    fact.setIssueIid(rs.getLong("issue_iid"));
    fact.setTitle(title);
    fact.setIssueState(closed ? "closed" : "opened");
    fact.setMilestoneTitle(defaultText(rs.getString("milestone_title")));
    fact.setAuthorName(defaultText(rs.getString("author_name")));
    fact.setHandlerName(defaultText(rs.getString("handler_name")));
    fact.setAssigneeName(defaultText(rs.getString("assignee_names")));
    fact.setFixUser(defaultText(rs.getString("fix_user")));
    fact.setCreatedAtSource(createdAt);
    fact.setUpdatedAtSource(toLocalDateTime(rs.getTimestamp("updated_at")));
    fact.setOdsUpdatedAt(toLocalDateTime(rs.getTimestamp("ods_updated_at")));
    fact.setClosedAtSource(toLocalDateTime(rs.getTimestamp("closed_at")));
    fact.setModuleName(moduleNames.isEmpty() ? null : moduleNames.get(0));
    fact.setPrimaryModuleName(moduleNames.isEmpty() ? null : moduleNames.get(0));
    fact.setModuleNames(String.join(", ", moduleNames));
    fact.setFunctionName(IssueFactNormalizationRules.normalizeFunctionName(title));
    fact.setCustomerNames(String.join(", ", customerNames));
    fact.setTestingPhase(testingPhase);
    fact.setSeverityLevel(severityLevel);
    fact.setSeverityAlias(IssueFactNormalizationRules.normalizeSeverityAlias(labels));
    fact.setPriorityLevel(priorityLevel);
    fact.setUrgency(priorityLevel);
    fact.setBugStatus(IssueFactNormalizationRules.normalizeBugStatus(labels));
    fact.setCategory(IssueFactNormalizationRules.normalizeCategory(labels));
    fact.setReasonCategory(customerIssue
        ? IssueFactNormalizationRules.normalizeCustomerIssueReasonCategory(labels, notesText)
        : IssueFactNormalizationRules.normalizeReasonCategory(labels, notesText));
    fact.setSystemTestLabel(IssueFactNormalizationRules.normalizeSystemTestLabel(labels));
    fact.setLabelNames(String.join(", ", labels));
    fact.setExcluded(IssueFactNormalizationRules.isExcluded(labels, closed, fact.getProjectId()));
    fact.setExclusionReason(IssueFactNormalizationRules.exclusionReason(labels, closed, fact.getProjectId()));
    fact.setFixed(IssueFactNormalizationRules.isFixed(labels, closed));
    fact.setDelayIssue(IssueFactNormalizationRules.hasDelayFlag(labels, notesText));
    fact.setDelayReason(IssueFactNormalizationRules.normalizeDelayReason(labels, notesText));
    fact.setDelayCause(customerIssue
        ? IssueFactNormalizationRules.inferCustomerIssueDelayCause(labels, notesText)
        : IssueFactNormalizationRules.inferDelayCause(labels, notesText));
    fact.setRegression(IssueFactNormalizationRules.isRegression(labels, title));
    fact.setCrash(IssueFactNormalizationRules.isCrash(labels, title));
    fact.setLevel1Other(IssueFactNormalizationRules.isLevel1Other(labels, title));
    boolean fixed = Boolean.TRUE.equals(fact.getFixed());
    boolean fixedForIllegalCheck =
        StringUtils.hasText(fact.getBugStatus()) && fact.getBugStatus().contains("已修复");
    fact.setIllegal(customerIssue
        ? IssueFactNormalizationRules.isCustomerIssueIllegal(labels, moduleNames, notesText, fixedForIllegalCheck)
        : IssueFactNormalizationRules.isIllegal(labels, closed, moduleNames, notesText, fixedForIllegalCheck));
    fact.setIllegalReason(customerIssue
        ? IssueFactNormalizationRules.customerIssueIllegalReason(labels, moduleNames, notesText, fixedForIllegalCheck)
        : IssueFactNormalizationRules.illegalReason(labels, closed, moduleNames, notesText, fixedForIllegalCheck));
    fact.setIllegalReasons(String.join(", ", customerIssue
        ? IssueFactNormalizationRules.customerIssueIllegalReasons(labels, moduleNames, notesText, fixedForIllegalCheck)
        : IssueFactNormalizationRules.illegalReasons(labels, closed, moduleNames, notesText, fixedForIllegalCheck)));
    fact.setHasResponse(IssueFactNormalizationRules.hasResponse(notesText));
    boolean responseDelayed = openCustomerIssue
        && IssueFactNormalizationRules.isResponseDelayed(labels, notesText, createdAt, priorityLevel, now);
    fact.setResearchTemplateTime(toLocalDateTime(rs.getTimestamp("research_template_time")));
    fact.setResponseOverdue(responseDelayed);
    fact.setResponseDelayed(responseDelayed);
    fact.setResolveSlaDays(resolveSlaDays);
    fact.setResolveDeadlineAt(resolveDeadlineAt);
    fact.setPlannedResolutionAt(responseTemplate.plannedResolutionAt());
    fact.setPlannedResolutionText(responseTemplate.plannedResolutionText());
    fact.setPlannedMergeVersionBranch(responseTemplate.plannedMergeVersionBranch());
    fact.setFixedLabelTime(
        Boolean.TRUE.equals(fact.getFixed())
            && StringUtils.hasText(fact.getBugStatus())
            && fact.getBugStatus().contains("已修复/完成")
            ? toLocalDateTime(rs.getTimestamp("fixed_label_time"))
            : null);
    fact.setResolveDelayed(openCustomerIssue && IssueFactNormalizationRules.isResolveDelayed(
        labels,
        Boolean.TRUE.equals(fact.getFixed()),
        IssueFactNormalizationRules.hasFixCaseNote(notesText),
        resolveDeadlineAt,
        now));
    fact.setLegacy(IssueFactNormalizationRules.isLegacy(
        labels,
        closed,
        createdAt,
        phaseCalendar == null ? null : phaseCalendar.phaseStartAt()));
    fact.setDeleted(false);
    return fact;
  }

  private boolean isCustomerIssueIssueFact(List<String> labels, Long projectId, String projectName, LocalDateTime createdAt) {
    boolean inCustomerDateRange = CustomerIssueScopeRules.isInCustomerIssueDateRange(createdAt);
    return inCustomerDateRange && CustomerIssueScopeRules.isCustomerProject(projectId, projectName);
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

  private MergeRequestFact mapMergeRequestFact(
      ResultSet rs, int rowNum, String sourceInstance, ModuleDictionary moduleDictionary) throws SQLException {
    List<String> labels = readTextArray(rs.getArray("label_titles"));
    List<String> projectLabels = readTextArray(rs.getArray("project_label_titles"));
    MergeRequestFact fact = new MergeRequestFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(sourceInstance);
    fact.setIngestChannel(MIRROR_INGEST_CHANNEL);
    fact.setSourceSummary(defaultText(rs.getString("metric_source_summary"), "GitLab merge request 镜像聚合"));
    fact.setRawPayload(defaultText(rs.getString("metric_raw_payload"), null));
    fact.setProjectId(rs.getLong("project_id"));
    fact.setProjectName(IssueFactNormalizationRules.normalizeMergeRequestProjectName(projectLabels));
    fact.setRepositoryName(defaultText(rs.getString("repository_name")));
    fact.setMergeRequestId(rs.getLong("merge_request_id"));
    fact.setMergeRequestIid(rs.getLong("merge_request_iid"));
    fact.setTitle(defaultText(rs.getString("title")));
    fact.setMergeRequestState(mergeRequestState(rs));
    fact.setTargetBranch(defaultText(rs.getString("target_branch")));
    fact.setSourceBranch(defaultText(rs.getString("source_branch")));
    fact.setAuthorName(defaultText(rs.getString("author_name")));
    fact.setMergeUserName(defaultText(rs.getString("merge_user_name")));
    fact.setOwnerName(defaultText(rs.getString("owner_name")));
    fact.setReviewerNames(defaultText(rs.getString("reviewer_names")));
    fact.setAssigneeNames(defaultText(rs.getString("assignee_names")));
    List<String> mergeRequestModules =
        moduleDictionary.normalizeMergeRequestModules(
            rs.getLong("project_id"),
            IssueFactNormalizationRules.normalizeMergeRequestModuleNames(labels));
    fact.setModuleName(String.join(" & ", mergeRequestModules));
    fact.setLabelNames(String.join(", ", labels));
    fact.setCreatedAtSource(toLocalDateTime(rs.getTimestamp("created_at")));
    fact.setUpdatedAtSource(toLocalDateTime(rs.getTimestamp("updated_at")));
    fact.setOdsUpdatedAt(toLocalDateTime(rs.getTimestamp("ods_updated_at")));
    fact.setMergedAtSource(toLocalDateTime(rs.getTimestamp("merged_at")));
    String reviewExceptionReason = defaultText(rs.getString("review_exception_reason"));
    boolean noNeedReview = "无需走查".equals(defaultText(rs.getString("reviewer_names")))
        || "无需走查扫描".equals(defaultText(rs.getString("reviewer_names")));
    fact.setReviewStatus(rs.getObject("review_duration_minutes") == null && !noNeedReview ? "PENDING" : "COMPLETED");
    fact.setReviewDurationMinutes((Integer) rs.getObject("review_duration_minutes"));
    fact.setReviewExceptionReason(reviewExceptionReason);
    fact.setCodeWalkthroughDate(toLocalDateTime(rs.getTimestamp("code_walkthrough_date")));
    fact.setCommentRate((BigDecimal) rs.getObject("comment_rate"));
    fact.setCommentRateSource(defaultText(rs.getString("comment_rate_source")));
    fact.setDefectCount((Integer) rs.getObject("defect_count"));
    fact.setDefectCountSource(defaultText(rs.getString("defect_count_source")));
    fact.setScanStatus(defaultText(rs.getString("scan_status")));
    fact.setScanBugCount((Integer) rs.getObject("scan_bug_count"));
    fact.setAnnotationRateResult(defaultText(rs.getString("annotation_rate_result")));
    fact.setBugCountResult(defaultText(rs.getString("bug_count_result")));
    fact.setAddedLines((Integer) rs.getObject("added_lines"));
    fact.setDeletedLines((Integer) rs.getObject("deleted_lines"));
    fact.setCodeSpecificationCount((Integer) rs.getObject("code_specification_count"));
    fact.setCodeLogicSpecificationCount((Integer) rs.getObject("code_logic_specification_count"));
    fact.setPerformanceSpecificationCount((Integer) rs.getObject("performance_specification_count"));
    fact.setDesignSpecificationCount((Integer) rs.getObject("design_specification_count"));
    fact.setOtherSpecificationCount((Integer) rs.getObject("other_specification_count"));
    fact.setReviewSpeedLocPerHour(
        defaultInteger((Integer) rs.getObject("review_speed_loc_per_hour"), reviewSpeedLocPerHour(fact)));
    fact.setReviewSpeedKlocPerHour(
        defaultBigDecimal((BigDecimal) rs.getObject("review_speed_kloc_per_hour"), reviewSpeedKlocPerHour(fact)));
    fact.setReviewDefectDensityPerKloc(
        defaultBigDecimal(
            (BigDecimal) rs.getObject("review_defect_density_per_kloc"),
            reviewDefectDensityPerKloc(fact)));
    fact.setReviewEfficiencyPerHour(
        defaultBigDecimal(
            (BigDecimal) rs.getObject("review_efficiency_per_hour"),
            reviewEfficiencyPerHour(fact)));
    fact.setCommitCount((Integer) rs.getObject("commit_count"));
    fact.setCommitRate(defaultInteger((Integer) rs.getObject("commit_rate"), commitRate(fact)));
    fact.setFunctionName(defaultText(rs.getString("function_name")));
    fact.setClangAddedLineCount((Integer) rs.getObject("clang_added_line_count"));
    fact.setDeleted(false);
    return fact;
  }

  private Integer reviewSpeedLocPerHour(MergeRequestFact fact) {
    if (fact.getAddedLines() == null
        || fact.getReviewDurationMinutes() == null
        || fact.getReviewDurationMinutes() <= 0) {
      return null;
    }
    return (int) Math.round(fact.getAddedLines() * 60.0 / fact.getReviewDurationMinutes());
  }

  private BigDecimal reviewSpeedKlocPerHour(MergeRequestFact fact) {
    Integer locPerHour = fact.getReviewSpeedLocPerHour();
    return locPerHour == null ? null : BigDecimal.valueOf(locPerHour / 1000.0).setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private BigDecimal reviewDefectDensityPerKloc(MergeRequestFact fact) {
    if (fact.getDefectCount() == null || fact.getAddedLines() == null || fact.getAddedLines() <= 0) {
      return null;
    }
    return BigDecimal.valueOf(fact.getDefectCount() * 1000.0 / fact.getAddedLines())
        .setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private BigDecimal reviewEfficiencyPerHour(MergeRequestFact fact) {
    if (fact.getDefectCount() == null
        || fact.getReviewDurationMinutes() == null
        || fact.getReviewDurationMinutes() <= 0) {
      return null;
    }
    return BigDecimal.valueOf(fact.getDefectCount() * 60.0 / fact.getReviewDurationMinutes())
        .setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private Integer commitRate(MergeRequestFact fact) {
    if (fact.getAddedLines() == null
        || fact.getCommitCount() == null
        || fact.getAddedLines() <= 0
        || fact.getCommitCount() <= 0) {
      return null;
    }
    return fact.getAddedLines() / fact.getCommitCount();
  }

  private Integer defaultInteger(Integer value, Integer fallback) {
    return value == null ? fallback : value;
  }

  private BigDecimal defaultBigDecimal(BigDecimal value, BigDecimal fallback) {
    return value == null ? fallback : value;
  }

  private void batchUpsertIssueFacts(List<IssueFact> facts) {
    for (List<IssueFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      issueFactPersistenceService.upsertIssueFacts(batch);
      refreshIssueFactSearchIndexes(batch);
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

  private void batchUpsertMergeRequestFacts(List<MergeRequestFact> facts) {
    for (List<MergeRequestFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      mergeRequestFactPersistenceService.upsertFacts(batch);
      refreshMergeRequestFactSearchIndexes(batch);
    }
  }

  private void refreshIssueFactSearchIndexesInBatches(List<IssueFact> facts) {
    for (List<IssueFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      refreshIssueFactSearchIndexes(batch);
    }
  }

  private void refreshMergeRequestFactSearchIndexesInBatches(List<MergeRequestFact> facts) {
    for (List<MergeRequestFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      refreshMergeRequestFactSearchIndexes(batch);
    }
  }

  private void refreshIssueFactSearchIndexes(List<IssueFact> facts) {
    jdbcTemplate.batchUpdate(
        """
        update issue_fact
           set search_text = ?,
               search_compact = ?,
               search_spell = ?,
               search_initials = ?,
               title_search_text = ?,
               title_search_compact = ?,
               title_search_spell = ?,
               title_search_initials = ?,
               module_search_text = ?,
               module_search_compact = ?,
               module_search_spell = ?,
               module_search_initials = ?,
               milestone_search_text = ?,
               milestone_search_compact = ?,
               milestone_search_spell = ?,
               milestone_search_initials = ?,
               author_search_text = ?,
               author_search_compact = ?,
               author_search_spell = ?,
               author_search_initials = ?,
               assignee_search_text = ?,
               assignee_search_compact = ?,
               assignee_search_spell = ?,
               assignee_search_initials = ?,
               primary_phase_label = ?,
               phase_filter_value = ?,
               phase_search_text = ?,
               phase_search_compact = ?,
               phase_search_spell = ?,
               phase_search_initials = ?
         where source_system = ?
           and source_instance = ?
           and project_id = ?
           and issue_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        (statement, fact) -> bindIssueSearchIndex(statement, fact));
  }

  private void bindIssueSearchIndex(PreparedStatement statement, IssueFact fact) throws SQLException {
    FactSearchIndexSupport.IssueSearchIndexes indexes = FactSearchIndexSupport.buildIssueIndexes(fact);
    int index = 1;
    index = bindSearchIndex(statement, index, indexes.keyword());
    index = bindSearchIndex(statement, index, indexes.title());
    index = bindSearchIndex(statement, index, indexes.module());
    index = bindSearchIndex(statement, index, indexes.milestone());
    index = bindSearchIndex(statement, index, indexes.author());
    index = bindSearchIndex(statement, index, indexes.assignee());
    statement.setString(index++, indexes.primaryPhaseLabel());
    statement.setString(index++, indexes.phaseFilterValue());
    index = bindSearchIndex(statement, index, indexes.phase());
    statement.setString(index++, fact.getSourceSystem());
    statement.setString(index++, fact.getSourceInstance());
    statement.setLong(index++, fact.getProjectId());
    statement.setLong(index, fact.getIssueId());
  }

  private void refreshMergeRequestFactSearchIndexes(List<MergeRequestFact> facts) {
    jdbcTemplate.batchUpdate(
        """
        update merge_request_fact
           set search_text = ?,
               search_compact = ?,
               search_spell = ?,
               search_initials = ?,
               owner_search_text = ?,
               owner_search_compact = ?,
               owner_search_spell = ?,
               owner_search_initials = ?
         where source_system = ?
           and source_instance = ?
           and project_id = ?
           and merge_request_id = ?
        """,
        facts,
        FACT_BATCH_SIZE,
        (statement, fact) -> bindMergeRequestSearchIndex(statement, fact));
  }

  private void bindMergeRequestSearchIndex(PreparedStatement statement, MergeRequestFact fact)
      throws SQLException {
    FactSearchIndexSupport.MergeRequestSearchIndexes indexes =
        FactSearchIndexSupport.buildMergeRequestIndexes(fact);
    int index = 1;
    index = bindSearchIndex(statement, index, indexes.keyword());
    index = bindSearchIndex(statement, index, indexes.owner());
    statement.setString(index++, fact.getSourceSystem());
    statement.setString(index++, fact.getSourceInstance());
    statement.setLong(index++, fact.getProjectId());
    statement.setLong(index, fact.getMergeRequestId());
  }

  private int bindSearchIndex(
      PreparedStatement statement, int parameterIndex, TextQuerySupport.SearchIndex index)
      throws SQLException {
    statement.setString(parameterIndex++, index.normalized());
    statement.setString(parameterIndex++, index.compact());
    statement.setString(parameterIndex++, index.spell());
    statement.setString(parameterIndex++, index.initials());
    return parameterIndex;
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

  private boolean isClosed(ResultSet rs) throws SQLException {
    Timestamp closedAt = rs.getTimestamp("closed_at");
    Integer stateId = (Integer) rs.getObject("state_id");
    return closedAt != null || (stateId != null && stateId != 1);
  }

  private String mergeRequestState(ResultSet rs) throws SQLException {
    Integer stateId = (Integer) rs.getObject("state_id");
    if (stateId != null) {
      return switch (stateId) {
        case 3 -> "merged";
        case 2 -> "closed";
        default -> "opened";
      };
    }
    return toLocalDateTime(rs.getTimestamp("merged_at")) == null ? "opened" : "merged";
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private Long nullableLong(ResultSet rs, String columnName) throws SQLException {
    Object value = rs.getObject(columnName);
    return value instanceof Number number ? number.longValue() : null;
  }

  private List<String> readTextArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (!(raw instanceof Object[] values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object value : values) {
      String normalized = defaultText(value == null ? null : String.valueOf(value), null);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return result;
  }

  private String normalizeKey(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
  }

  private String defaultText(String value) {
    return defaultText(value, "");
  }

  private String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private record PhaseCalendarKey(Long projectId, String testingPhase) {
  }

  private record PhaseCalendarEntry(
      Long projectId,
      String testingPhase,
      LocalDateTime phaseStartAt,
      LocalDateTime phaseEndAt,
      boolean enabled) {
  }
}
