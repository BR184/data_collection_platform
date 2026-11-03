package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.FormAdoptionProperties;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarEntry;
import com.data.collection.platform.service.IssuePhaseCalendarLoader.PhaseCalendarKey;
import com.data.collection.platform.service.read.BypassPublicationRequest;
import com.data.collection.platform.service.read.FactReadVisibility;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

  private final JdbcTemplate jdbcTemplate;
  private final IssueFactPersistenceService issueFactPersistenceService;
  private final IssueCustomerNameAliasService issueCustomerNameAliasService;
  private final MergeRequestFactPersistenceService mergeRequestFactPersistenceService;
  private final ModuleDictionaryService moduleDictionaryService;
  private final FactBuildTaskService factBuildTaskService;
  private final SyncFactPublicationStateService publicationStateService;
  private final GitlabSourceSchemaGuard sourceSchemaGuard;
  private final GitlabConfigService configService;
  private final FactTargetPublicationService factTargetPublicationService;
  private final GitlabFactSourceSqlProvider factSourceSqlProvider;
  private final GitlabFactSourceQueryExecutor factSourceQueryExecutor;
  private final IssuePhaseCalendarLoader phaseCalendarLoader;
  private final IssueFactSourceRowMapper issueRowMapper;
  private final MergeRequestFactSourceRowMapper mergeRequestRowMapper;
  private final FactSearchIndexRepairService searchIndexRepairService;
  private final FactPublicationTransaction publicationTransaction;
  private final GitlabMirrorProperties mirrorProperties;
  private final FormIssueInputResolver formIssueInputResolver;
  private final FormIssueAuthorityRegistrar formIssueAuthorityRegistrar;
  private final FormIssueBuildInputService formIssueBuildInputService;
  private final FormAdoptionProperties formAdoptionProperties;
  private final FormMergeRequestBuildInputService formMergeRequestBuildInputService;
  private final com.data.collection.platform.service.form.FormReviewProjectionService
      formReviewProjectionService;

  public FactBuildService(
      JdbcTemplate jdbcTemplate,
      IssueFactPersistenceService issueFactPersistenceService,
      IssueCustomerNameAliasService issueCustomerNameAliasService,
      MergeRequestFactPersistenceService mergeRequestFactPersistenceService,
      ModuleDictionaryService moduleDictionaryService,
      FactBuildTaskService factBuildTaskService,
      SyncFactPublicationStateService publicationStateService,
      GitlabSourceSchemaGuard sourceSchemaGuard,
      GitlabConfigService configService,
      FactTargetPublicationService factTargetPublicationService,
      GitlabFactSourceSqlProvider factSourceSqlProvider,
      GitlabFactSourceQueryExecutor factSourceQueryExecutor,
      IssuePhaseCalendarLoader phaseCalendarLoader,
      IssueFactSourceRowMapper issueRowMapper,
      MergeRequestFactSourceRowMapper mergeRequestRowMapper,
      FactSearchIndexRepairService searchIndexRepairService,
      FactPublicationTransaction publicationTransaction,
      GitlabMirrorProperties mirrorProperties,
      FormIssueInputResolver formIssueInputResolver,
      FormIssueAuthorityRegistrar formIssueAuthorityRegistrar,
      FormIssueBuildInputService formIssueBuildInputService,
      FormAdoptionProperties formAdoptionProperties,
      FormMergeRequestBuildInputService formMergeRequestBuildInputService,
      com.data.collection.platform.service.form.FormReviewProjectionService
          formReviewProjectionService) {
    this.jdbcTemplate = jdbcTemplate;
    this.issueFactPersistenceService = issueFactPersistenceService;
    this.issueCustomerNameAliasService = issueCustomerNameAliasService;
    this.mergeRequestFactPersistenceService = mergeRequestFactPersistenceService;
    this.moduleDictionaryService = moduleDictionaryService;
    this.factBuildTaskService = factBuildTaskService;
    this.publicationStateService = publicationStateService;
    this.sourceSchemaGuard = sourceSchemaGuard;
    this.configService = configService;
    this.factTargetPublicationService = factTargetPublicationService;
    this.factSourceSqlProvider = factSourceSqlProvider;
    this.factSourceQueryExecutor = factSourceQueryExecutor;
    this.phaseCalendarLoader = phaseCalendarLoader;
    this.issueRowMapper = issueRowMapper;
    this.mergeRequestRowMapper = mergeRequestRowMapper;
    this.searchIndexRepairService = searchIndexRepairService;
    this.publicationTransaction = publicationTransaction;
    this.mirrorProperties = mirrorProperties;
    this.formIssueInputResolver = formIssueInputResolver;
    this.formIssueAuthorityRegistrar = formIssueAuthorityRegistrar;
    this.formIssueBuildInputService = formIssueBuildInputService;
    this.formAdoptionProperties = formAdoptionProperties;
    this.formMergeRequestBuildInputService = formMergeRequestBuildInputService;
    this.formReviewProjectionService = formReviewProjectionService;
  }

  /**
   * 组装 GitLab 事实族旁路发布输入。
   *
   * <p>来源完整代际证明来自同步控制面（依赖表代际、发布状态、变化头水位），发布层不接受调用方自造的证明；
   * 旁路不消费变化头，因此覆盖水位取 0，仅表示“不宣称覆盖任何待发布变化”。
   */
  private BypassPublicationRequest gitlabBypassRequest(String sourceInstance, FactType factType) {
    return new BypassPublicationRequest(
        ReadPublicationDomainKey.gitlabFact(sourceInstance, factType),
        DEFAULT_SOURCE_SYSTEM,
        publicationStateService.sourceSeal(sourceInstance, factType, 0L),
        0L,
        null,
        null,
        Instant.now(), false);
  }

  public FactBuildResponse rebuildIssueFactsForQueuedTask(
      GitlabSyncConfig config, FactRevisionTarget target, boolean full, FactBuildProgress progress) {
    return rebuildIssueFactsInternal(
        target, full, GitlabSourceInstanceSupport.sourceInstanceOf(config), progress);
  }

  /**
   * 用当前来源状态替换单个 Issue 的事实投影。
   *
   * @param sourceInstance GitLab 来源实例
   * @param projectId 项目 ID
   * @param issueIid 项目内 Issue IID
   * @return 当前来源存在时为写入结果，不存在时为删除结果
   */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public FactBuildResponse rebuildIssueFactByIid(String sourceInstance, Long projectId, Long issueIid) {
    if (projectId == null || issueIid == null) {
      throw new BizException("刷新单条议题需要项目 ID 和议题编号");
    }
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    requireIssueSourceHistoryComplete(normalizedSource);
    Long rootId = findIssueRootId(normalizedSource, projectId, issueIid);
    if (rootId == null) {
      return new FactBuildResponse(
          factScope("issue", normalizedSource), false, 0, "未找到对应议题事实根");
    }
    FactBuildResponse response =
        factTargetPublicationService.publishWithinCandidate(
            gitlabBypassRequest(normalizedSource, FactType.ISSUE),
            target -> rebuildIssueFactsByRootIds(target, List.of(rootId)));
    return new FactBuildResponse(
        factScope("issue", normalizedSource),
        false,
        response.affectedRows(),
        response.affectedRows() == 0 ? "对应议题事实已按当前来源删除" : "议题事实已按单条刷新");
  }

  /**
   * 在候选修订内原子替换指定 Issue 目标范围的事实投影。
   *
   * <p>目标来源为空时只登记删除证明，旧已发布修订由发布会话关闭，不能退化为无操作。
   *
   * @param target 候选修订写入目标
   * @param rootIds GitLab Issue 数据库根 ID 集合
   * @return 当前仍存在并写入的事实数量
   */
  @Transactional
  public FactBuildResponse rebuildIssueFactsByRootIds(
      FactRevisionTarget target, List<Long> rootIds) {
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(target.sourceInstance());
    List<Long> safeRootIds = distinctRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      return new FactBuildResponse(factScope("issue", normalizedSource), false, 0, "没有需要刷新的议题事实");
    }
    requireIssueSourceHistoryComplete(normalizedSource);
    sourceSchemaGuard.verifyIssueFactSource(normalizedSource);
    Map<PhaseCalendarKey, PhaseCalendarEntry> calendar =
        phaseCalendarLoader.loadFactProjectionCalendar();
    ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
    Map<String, String> customerNameAliases = issueCustomerNameAliasService.loadAliases();
    List<IssueFact> facts =
        loadIssueFactsByRootIds(
            normalizedSource, safeRootIds, calendar, moduleDictionary, customerNameAliases);
    FormIssueBuildInputService.BuildInputs inputs =
        freezeFormInputs(target, normalizedSource, facts, currentGitlabSourceTime());
    applyFormIssueInputs(normalizedSource, facts, inputs.hashes());
    issueFactPersistenceService.replaceRootFacts(target, safeRootIds, facts);
    searchIndexRepairService.refreshIssueFactSearchIndexesInBatches(target, facts);
    return new FactBuildResponse(
        factScope("issue", normalizedSource),
        false,
        facts.size(),
        "议题事实已按受影响对象刷新");
  }

  private FactBuildResponse rebuildIssueFactsInternal(
      FactRevisionTarget target, boolean full, String sourceInstance, FactBuildProgress progress) {
    requireIssueSourceHistoryComplete(sourceInstance);
    sourceSchemaGuard.verifyIssueFactSource(sourceInstance);
    LocalDateTime changedSince = full ? null : getIssueFactChangedSince(sourceInstance);
    try {
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar =
          phaseCalendarLoader.loadFactProjectionCalendar();
      ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
      Map<String, String> customerNameAliases = issueCustomerNameAliasService.loadAliases();
      List<IssueFact> facts =
          loadIssueFacts(sourceInstance, changedSince, calendar, moduleDictionary, customerNameAliases);
      FormIssueBuildInputService.BuildInputs inputs =
          freezeFormInputs(target, sourceInstance, facts, currentGitlabSourceTime());
      applyFormIssueInputs(sourceInstance, facts, inputs.hashes());
      if (full) {
        publishFullIssueSnapshot(target, sourceInstance, facts, progress);
      } else {
        // 增量范围有界，保持单事务原子语义：事实、客户成员与搜索列同提交；目录在全部候选事实完成后统一准备。
        publicationTransaction.execute(() -> {
          batchUpsertIssueFacts(target, facts);
          return null;
        });
      }
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

  private void requireIssueSourceHistoryComplete(String sourceInstance) {
    if (!publicationStateService.isIssueSourceHistoryComplete(sourceInstance)) {
      throw new BizException(
          "来源 "
              + GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance)
              + " 的 resource_label_events 尚未完成完整全量核验，暂不构建 ISSUE 事实");
    }
  }

  /**
   * 分批提交完整议题事实快照为候选修订。
   *
   * <p>全量构建使用新代际：来源已消失的根在新代际里自然不存在，无需原地清理。
   * 每个批次在独立事务内原子完成事实+客户成员 upsert 与搜索列刷新；批提交后回调进度
   * （续期任务租约并上报可见进度）。
   */
  private void publishFullIssueSnapshot(
      FactRevisionTarget target, String sourceInstance, List<IssueFact> facts, FactBuildProgress progress) {
    int totalChunks = chunkCount(facts.size());
    int chunkIndex = 0;
    long processedRows = 0;
    for (List<IssueFact> chunk : partition(facts, mirrorProperties.getFactFullBuildChunkSize())) {
      publicationTransaction.execute(() -> {
        issueFactPersistenceService.upsertIssueFacts(target, chunk);
        searchIndexRepairService.refreshIssueFactSearchIndexesInBatches(target, chunk);
        return null;
      });
      processedRows += chunk.size();
      progress.chunkCommitted(++chunkIndex, totalChunks, processedRows);
    }
  }

  private int chunkCount(int size) {
    int chunkSize = Math.max(1, mirrorProperties.getFactFullBuildChunkSize());
    return (size + chunkSize - 1) / chunkSize;
  }

  /**
   * 按当前时间重算客户问题的响应/解决延期三列，写入候选修订，等待投影与完整发布。
   *
   * <p>复制原修订和成员集合，只改变三列判定；公开 generation 在完整发布事务中才推进。
   */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public FactBuildResponse refreshCustomerIssueDelayFactsForConfig(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    // 捕获在候选内绑定，consumed 由最终公开事务复核后推进；此处只准备候选。
    return factTargetPublicationService.publishWithinCandidate(
        gitlabBypassRequest(sourceInstance, FactType.ISSUE),
        target -> refreshCustomerIssueDelayFactsWithinCandidate(target, sourceInstance));
  }

  /**
   * 周期重算：先冻结全部议题的表单输入证据，再按签名差异决定完整重建；签名未变的已消费输入
   * 只重算时间判定并窄写三列。COMMENT 保持原三列窄写。
   */
  private FactBuildResponse refreshCustomerIssueDelayFactsWithinCandidate(
      FactRevisionTarget target, String sourceInstance) {
    List<IssueFact> facts = loadOpenCustomerIssueFacts(sourceInstance);
    LocalDateTime now = currentGitlabSourceTime();
    FormIssueBuildInputService.BuildInputs inputs =
        freezeFormInputs(target, sourceInstance, facts, now);
    Map<FormIssueInputResolver.IssueRef, String> hashes = inputs.hashes();
    java.util.Set<Long> rebuildRoots = new java.util.LinkedHashSet<>();
    for (IssueFact fact : facts) {
      FormIssueInputResolver.IssueRef ref = issueRefOf(fact);
      if (ref == null || !hashes.containsKey(ref)) {
        continue;
      }
      if (!java.util.Objects.equals(fact.getFormInputHash(), hashes.get(ref))) {
        rebuildRoots.add(fact.getIssueId());
      }
    }
    FormIssueInputResolver.Resolution resolution = resolveAdoptedInputs(sourceInstance, facts);
    List<IssueFact> changedFacts = new ArrayList<>();
    for (IssueFact fact : facts) {
      if (rebuildRoots.contains(fact.getIssueId())) {
        continue;
      }
      FormIssueInputResolver.IssueRef ref = issueRefOf(fact);
      IssueBusinessInput formInput = ref == null ? null : resolution.inputs().get(ref);
      boolean nextResponseDelayed;
      boolean nextResolveDelayed;
      if (formInput != null) {
        IssueSlaRules.Evaluation evaluation =
            IssueBusinessProjector.evaluate(fact, formInput, labelsOf(fact), now);
        nextResponseDelayed = evaluation.responseDelayed();
        nextResolveDelayed = evaluation.resolveDelayed();
      } else {
        nextResponseDelayed =
            IssueFactNormalizationRules.isResponseDelayed(
                labelsOf(fact),
                fact.getRawPayload(),
                fact.getCreatedAtSource(),
                fact.getPriorityLevel(),
                now);
        nextResolveDelayed =
            IssueFactNormalizationRules.isResolveDelayed(
                labelsOf(fact),
                IssueFactNormalizationRules.hasFixCaseNote(fact.getRawPayload()),
                fact.getResolveDeadlineAt(),
                now);
      }
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
    if (!rebuildRoots.isEmpty()) {
      // 消费端完整发布：业务列、原因/合规与三列判定来自同一份冻结输入。
      rebuildIssueFactsWithinCandidate(target, List.copyOf(rebuildRoots), hashes);
    }
    List<IssueFact> updatedFacts = upsertCustomerIssueDelayFacts(target, changedFacts);
    return new FactBuildResponse(factScope("customer-issue-delay", sourceInstance), false,
        updatedFacts.size() + rebuildRoots.size(),
        "客户问题延期候选已构建，等待投影与统一发布");
  }

  /** 冻结候选内全部议题的表单输入；未消费 head 不能被他人持有。 */
  private FormIssueBuildInputService.BuildInputs freezeFormInputs(
      FactRevisionTarget target, String sourceInstance, List<IssueFact> facts, LocalDateTime now) {
    Map<FormIssueInputResolver.IssueRef, IssueFact> candidates = factsWithIssueRefs(facts);
    if (candidates.isEmpty()) {
      return new FormIssueBuildInputService.BuildInputs(Map.of(), 0);
    }
    registerFormAuthorities(sourceInstance, candidates);
    return formIssueBuildInputService.freeze(target, sourceInstance, candidates.keySet(), now);
  }

  /** 在候选内按已冻结签名重建指定议题事实，不再重新领取或改绑证据。 */
  private FactBuildResponse rebuildIssueFactsWithinCandidate(
      FactRevisionTarget target,
      List<Long> rootIds,
      Map<FormIssueInputResolver.IssueRef, String> hashes) {
    String sourceInstance =
        GitlabSourceInstanceSupport.normalizeSourceInstance(target.sourceInstance());
    List<Long> safeRootIds = distinctRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      return new FactBuildResponse(factScope("issue", sourceInstance), false, 0, "没有需要刷新的议题事实");
    }
    requireIssueSourceHistoryComplete(sourceInstance);
    sourceSchemaGuard.verifyIssueFactSource(sourceInstance);
    Map<PhaseCalendarKey, PhaseCalendarEntry> calendar =
        phaseCalendarLoader.loadFactProjectionCalendar();
    ModuleDictionary moduleDictionary = moduleDictionaryService.loadDictionary();
    Map<String, String> customerNameAliases = issueCustomerNameAliasService.loadAliases();
    List<IssueFact> facts =
        loadIssueFactsByRootIds(
            sourceInstance, safeRootIds, calendar, moduleDictionary, customerNameAliases);
    applyFormIssueInputs(sourceInstance, facts, hashes);
    issueFactPersistenceService.replaceRootFacts(target, safeRootIds, facts);
    searchIndexRepairService.refreshIssueFactSearchIndexesInBatches(target, facts);
    return new FactBuildResponse(
        factScope("issue", sourceInstance), false, facts.size(), "议题事实已按冻结输入重建");
  }

  /**
   * 对启用点之后创建的议题应用表单业务输入；未配置启用点或历史议题保持评论口径不变。
   *
   * <p>输入按范围与项目批量读取，不逐议题回表；空输入表示新资源尚未填写，业务列随之为空。
   * 业务值与事实签名都来自冻结之后读取的同一份证据。
   */
  private void applyFormIssueInputs(
      String sourceInstance, List<IssueFact> facts, Map<FormIssueInputResolver.IssueRef, String> hashes) {
    Map<FormIssueInputResolver.IssueRef, IssueFact> candidates = factsWithIssueRefs(facts);
    if (candidates.isEmpty()) {
      return;
    }
    FormIssueInputResolver.Resolution resolution =
        formIssueInputResolver.resolveAll(sourceInstance, candidates.keySet());
    reportPendingRegistration(resolution);
    LocalDateTime now = currentGitlabSourceTime();
    for (Map.Entry<FormIssueInputResolver.IssueRef, IssueBusinessInput> entry :
        resolution.inputs().entrySet()) {
      IssueFact fact = candidates.get(entry.getKey());
      IssueBusinessProjector.apply(fact, entry.getValue(), labelsOf(fact), now);
    }
    for (Map.Entry<FormIssueInputResolver.IssueRef, IssueFact> entry : candidates.entrySet()) {
      entry.getValue().setFormInputHash(hashes.get(entry.getKey()));
    }
  }

  private FormIssueInputResolver.Resolution resolveAdoptedInputs(
      String sourceInstance, List<IssueFact> facts) {
    Map<FormIssueInputResolver.IssueRef, IssueFact> candidates = factsWithIssueRefs(facts);
    if (candidates.isEmpty()) {
      return FormIssueInputResolver.Resolution.empty();
    }
    FormIssueInputResolver.Resolution resolution =
        formIssueInputResolver.resolveAll(sourceInstance, candidates.keySet());
    reportPendingRegistration(resolution);
    return resolution;
  }

  /** 只选具备完整资源键的议题；启用点只在首次登记内使用，不在消费时重新判断。 */
  private Map<FormIssueInputResolver.IssueRef, IssueFact> factsWithIssueRefs(
      List<IssueFact> facts) {
    Map<FormIssueInputResolver.IssueRef, IssueFact> candidates = new LinkedHashMap<>();
    for (IssueFact fact : facts) {
      FormIssueInputResolver.IssueRef ref = issueRefOf(fact);
      if (ref == null) {
        continue;
      }
      candidates.put(ref, fact);
    }
    return candidates;
  }

  private void registerFormAuthorities(
      String sourceInstance, Map<FormIssueInputResolver.IssueRef, IssueFact> candidates) {
    LocalDateTime enabledAt = formAdoptionProperties.getIssueEnabledAt();
    if (enabledAt == null) {
      return;
    }
    Map<FormIssueInputResolver.IssueRef, LocalDateTime> createdAtByRef = new LinkedHashMap<>();
    for (Map.Entry<FormIssueInputResolver.IssueRef, IssueFact> entry : candidates.entrySet()) {
      createdAtByRef.put(entry.getKey(), entry.getValue().getCreatedAtSource());
    }
    formIssueAuthorityRegistrar.registerMissing(
        sourceInstance, createdAtByRef, enabledAt, currentGitlabSourceTime());
  }

  private void reportPendingRegistration(FormIssueInputResolver.Resolution resolution) {
    if (!resolution.pendingRegistration().isEmpty()) {
      log.warn(
          "{} 个议题已登记表单范围但缺少持久权威，本次保持评论口径并等待首次登记",
          resolution.pendingRegistration().size());
    }
  }

  private FormIssueInputResolver.IssueRef issueRefOf(IssueFact fact) {
    if (fact == null || fact.getProjectId() == null || fact.getIssueIid() == null) {
      return null;
    }
    return new FormIssueInputResolver.IssueRef(fact.getProjectId(), fact.getIssueIid());
  }

  /**
   * 在候选内把三列判定写成新的议题修订。
   *
   * <p>入参是从库中读入的记忆快照；P2 起不再按主键原地改三列，而是在候选代际内写入新修订：
   * 数据库复制原行与成员集合，只替换三个延期判定列；不重解释其它业务列或搜索影子列。旧修订不被改写。
   *
   * @param target 候选修订写入目标
   * @param changedFacts 三列判定结果发生变化的事实行；空集合不产生任何写入
   * @return 已写入候选的修订对应的输入事实；写入 0 行时不产生范围推进
   */
  private List<IssueFact> upsertCustomerIssueDelayFacts(
      FactRevisionTarget target, List<IssueFact> changedFacts) {
    if (changedFacts.isEmpty()) {
      return List.of();
    }
    issueFactPersistenceService.copyDelayFlagRevisions(target, changedFacts);
    return changedFacts;
  }

  private List<IssueFact> loadIssueFacts(
      String sourceInstance,
      LocalDateTime changedSince,
      Map<PhaseCalendarKey, PhaseCalendarEntry> calendar,
      ModuleDictionary moduleDictionary,
      Map<String, String> customerNameAliases) {
    return queryIssueFacts(
        sourceInstance,
        factSourceSqlProvider.issueSourceSql(),
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
    return queryIssueFacts(
        sourceInstance,
        factSourceSqlProvider.issueSourceSqlForRoots(rootIds),
        null,
        new ArrayList<>(rootIds),
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

  public FactBuildResponse rebuildMergeRequestFactsForQueuedTask(
      GitlabSyncConfig config, FactRevisionTarget target, boolean full, FactBuildProgress progress) {
    return rebuildMergeRequestFactsInternal(
        target,
        full,
        GitlabSourceInstanceSupport.sourceInstanceOf(config),
        commitFactsEnabled(config),
        progress);
  }

  /**
   * 用当前来源状态替换单个 MR 的事实投影。
   *
   * @param sourceInstance GitLab 来源实例
   * @param projectId 目标项目 ID
   * @param mergeRequestIid 项目内 MR IID
   * @return 当前来源存在时为写入结果，不存在时为删除结果
   */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
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
    FactBuildResponse response =
        factTargetPublicationService.publishWithinCandidate(
            gitlabBypassRequest(normalizedSource, FactType.MERGE_REQUEST),
            target -> rebuildMergeRequestFactsByRootIds(target, List.of(rootId)));
    return new FactBuildResponse(
        factScope("merge-request", normalizedSource),
        false,
        response.affectedRows(),
        response.affectedRows() == 0
            ? "对应合并请求事实已按当前来源删除"
            : "合并请求事实已按单条刷新");
  }

  /**
   * 在候选修订内原子替换指定 MR 目标范围的事实投影。
   *
   * <p>目标来源为空时只登记删除证明，旧已发布修订由发布会话关闭，不能退化为无操作。
   *
   * @param target 候选修订写入目标
   * @param rootIds GitLab MR 数据库根 ID 集合
   * @return 当前仍存在并写入的事实数量
   */
  @Transactional
  public FactBuildResponse rebuildMergeRequestFactsByRootIds(
      FactRevisionTarget target, List<Long> rootIds) {
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(target.sourceInstance());
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
            factSourceSqlProvider.mergeRequestSourceSqlForRoots(normalizedSource, safeRootIds),
            "",
            null,
            new ArrayList<>(safeRootIds),
            (rs, rowNum) ->
                mergeRequestRowMapper.mapSource(rs, normalizedSource, moduleDictionary));
    List<MergeRequestCommitFact> commitFacts = commitFactsEnabled
        ? loadMergeRequestCommitFactsByRootIds(normalizedSource, safeRootIds)
        : List.of();
    formMergeRequestBuildInputService.freeze(
        target, normalizedSource, facts, LocalDateTime.now());
    com.data.collection.platform.service.form.FormReviewProjectionService.Projection projection =
        formReviewProjectionService.load(normalizedSource, facts);
    formReviewProjectionService.apply(facts, projection);
    mergeRequestFactPersistenceService.replaceRootFacts(
        target, safeRootIds, facts, commitFacts, projection.records());
    searchIndexRepairService.refreshMergeRequestFactSearchIndexesInBatches(target, facts);
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
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public boolean publishEnrichedMergeRequestFacts(
      String sourceInstance,
      List<Long> rootIds) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    FactBuildResponse response = factBuildTaskService.runGuarded(
        factScope("merge-request", normalizedSource),
        false,
        () ->
            factTargetPublicationService.publishWithinCandidate(
                gitlabBypassRequest(normalizedSource, FactType.MERGE_REQUEST),
                target -> rebuildMergeRequestFactsByRootIds(target, rootIds)));
    return !FactBuildTaskService.wasSkippedBecauseBusy(response);
  }

  private FactBuildResponse rebuildMergeRequestFactsInternal(
      FactRevisionTarget target,
      boolean full,
      String sourceInstance,
      boolean commitFactsEnabled,
      FactBuildProgress progress) {
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
            ? loadMergeRequestCommitFacts(sourceInstance)
            : List.of();
        publishFullMergeRequestSnapshot(target, sourceInstance, facts, commitFacts, progress);
      } else {
        List<Long> rootIds = facts.stream()
            .map(MergeRequestFact::getMergeRequestId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        if (!rootIds.isEmpty()) {
          // 增量范围有界，保持单事务原子语义：事实替换、提交关系、走查投影与搜索列刷新同提交。
          publicationTransaction.execute(() -> {
            formMergeRequestBuildInputService.freeze(
                target, sourceInstance, facts, LocalDateTime.now());
            com.data.collection.platform.service.form.FormReviewProjectionService.Projection
                projection = formReviewProjectionService.load(sourceInstance, facts);
            formReviewProjectionService.apply(facts, projection);
            mergeRequestFactPersistenceService.replaceRootFacts(
                target,
                rootIds,
                facts,
                commitFactsEnabled
                    ? loadMergeRequestCommitFactsByRootIds(sourceInstance, rootIds)
                    : List.of(),
                projection.records());
            searchIndexRepairService.refreshMergeRequestFactSearchIndexesInBatches(target, facts);
            return null;
          });
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

  /**
   * 分批提交完整合并请求事实快照为候选修订。
   *
   * <p>全量构建使用新代际：来源已消失的根在新代际里自然不存在，无需原地清理。
   * 每个批次在独立事务内原子完成 MR 事实、提交关系事实 upsert 与搜索列刷新；批提交后回调进度
   * （续期任务租约并上报可见进度）。
   */
  private void publishFullMergeRequestSnapshot(
      FactRevisionTarget target,
      String sourceInstance,
      List<MergeRequestFact> facts,
      List<MergeRequestCommitFact> commitFacts,
      FactBuildProgress progress) {
    Map<Long, List<MergeRequestCommitFact>> commitsByMergeRequest =
        commitFacts.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    MergeRequestCommitFact::mergeRequestId));
    int totalChunks = chunkCount(facts.size());
    int chunkIndex = 0;
    long processedRows = 0;
    for (List<MergeRequestFact> chunk : partition(facts, mirrorProperties.getFactFullBuildChunkSize())) {
      List<MergeRequestCommitFact> chunkCommits =
          chunk.stream()
              .map(MergeRequestFact::getMergeRequestId)
              .filter(java.util.Objects::nonNull)
              .flatMap(
                  mergeRequestId ->
                      commitsByMergeRequest.getOrDefault(mergeRequestId, List.of()).stream())
              .toList();
      List<Long> chunkRootIds =
          chunk.stream()
              .map(MergeRequestFact::getMergeRequestId)
              .filter(java.util.Objects::nonNull)
              .distinct()
              .sorted()
              .toList();
      publicationTransaction.execute(() -> {
        formMergeRequestBuildInputService.freeze(
            target, sourceInstance, chunk, LocalDateTime.now());
        com.data.collection.platform.service.form.FormReviewProjectionService.Projection
            projection = formReviewProjectionService.load(sourceInstance, chunk);
        formReviewProjectionService.apply(chunk, projection);
        mergeRequestFactPersistenceService.upsertFacts(target, chunk);
        mergeRequestFactPersistenceService.upsertCommitFacts(target, chunkCommits);
        mergeRequestFactPersistenceService.replaceFormReviewFacts(
            target, chunkRootIds, projection.records());
        searchIndexRepairService.refreshMergeRequestFactSearchIndexesInBatches(target, chunk);
        return null;
      });
      processedRows += chunk.size();
      progress.chunkCommitted(++chunkIndex, totalChunks, processedRows);
    }
  }

  private LocalDateTime getIssueFactChangedSince(String sourceInstance) {
    return jdbcTemplate.queryForObject(
        """
            select max(f.ods_updated_at)
              from issue_fact f
             where f.source_system = ?
               and f.source_instance = ?
               and """
            + FactReadVisibility.liveRevisionPredicate("f"),
        LocalDateTime.class,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance);
  }

  private LocalDateTime getMergeRequestFactChangedSince(String sourceInstance) {
    return jdbcTemplate.queryForObject(
        """
            select max(f.ods_updated_at)
              from merge_request_fact f
             where f.source_system = ?
               and f.source_instance = ?
               and """
            + FactReadVisibility.liveRevisionPredicate("f"),
        LocalDateTime.class,
            DEFAULT_SOURCE_SYSTEM,
            sourceInstance);
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
               and """
            + FactReadVisibility.liveRevisionPredicate("f"),
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
                  from issue_fact f
                 where f.source_system = ? and f.source_instance = ?
                   and f.project_id = ? and f.issue_iid = ?
                   and """
            + FactReadVisibility.liveRevisionPredicate("f")
            + """
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
                  from merge_request_fact f
                 where f.source_system = ? and f.source_instance = ?
                   and f.project_id = ? and f.merge_request_iid = ?
                   and """
            + FactReadVisibility.liveRevisionPredicate("f")
            + """
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

  /**
   * 装载全部合并请求提交关系，供全量构建发布完整快照。
   *
   * <p>"全部"是独立入口而不是"根集合为空"：空集合在按根口径里表示空结果（本类 {@code
   * loadMergeRequestCommitFactsByRootIds} 直接拒绝空集合），两者共用入参会让全量构建静默拿到零条并
   * 让随后的快照清理删掉既有提交关系。
   *
   * @param sourceInstance GitLab 数据源实例
   * @return 该实例全部提交关系
   */
  private List<MergeRequestCommitFact> loadMergeRequestCommitFacts(String sourceInstance) {
    return factSourceQueryExecutor.query(
        "merge-request-commit-fact-source-query",
        sourceInstance,
        factSourceSqlProvider.mergeRequestCommitSourceSql(),
        "",
        null,
        (resultSet, rowNumber) ->
            mergeRequestRowMapper.mapCommit(resultSet, sourceInstance));
  }

  /**
   * 只装载指定稳定根的提交关系，用于按根定向发布。
   *
   * @param sourceInstance GitLab 数据源实例
   * @param rootIds 稳定根集合，不能为空
   * @return 指定根的提交关系
   * @throws IllegalArgumentException 根集合为空或全为非法值
   */
  private List<MergeRequestCommitFact> loadMergeRequestCommitFactsByRootIds(
      String sourceInstance, List<Long> rootIds) {
    List<Long> safeRootIds = distinctRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      throw new IllegalArgumentException("按根装载合并请求提交关系必须给出稳定根集合");
    }
    return factSourceQueryExecutor.query(
        "merge-request-commit-fact-source-query",
        sourceInstance,
        factSourceSqlProvider.mergeRequestCommitSourceSqlForRoots(safeRootIds),
        "",
        null,
        new ArrayList<>(safeRootIds),
        (resultSet, rowNumber) ->
            mergeRequestRowMapper.mapCommit(resultSet, sourceInstance));
  }

  private void batchUpsertIssueFacts(FactRevisionTarget target, List<IssueFact> facts) {
    for (List<IssueFact> batch : partition(facts, FACT_BATCH_SIZE)) {
      issueFactPersistenceService.upsertIssueFacts(target, batch);
      searchIndexRepairService.refreshIssueFactSearchIndexes(target, batch);
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
