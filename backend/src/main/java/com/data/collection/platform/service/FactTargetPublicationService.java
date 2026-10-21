package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomain;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.service.read.BypassPublicationRequest;
import com.data.collection.platform.service.read.CandidateOpenRequest;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;

/**
 * 把一轮固定输入的有界构建批次归入同域候选；全部事实与必需投影完成后统一原子发布。
 *
 * <p>构建任务 SUCCESS 只表示候选事实批次成功，不推进公开指针或 heads。候选进入 PROJECTING 后，
 * P3 按冻结输入与定义登记真实投影完成证明，再调用唯一发布入口；失败、中止与让位保留上一完整版本。
 *
 * <p>本服务只负责读版本生命周期与候选写入编排；生产资格仍由 {@code SyncFactPublicationStateService} 管理。
 */
@Service
public class FactTargetPublicationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(FactTargetPublicationService.class);
  private static final String SOURCE_SYSTEM_GITLAB = "GITLAB";

  private final JdbcTemplate jdbcTemplate;
  private final FactProjectionScopeResolver scopeResolver;
  private final FactProjectionGenerationService generationService;
  private final com.data.collection.platform.service.sync.SyncRunPublicationFenceService
      publicationFenceService;
  private final SyncFactPublicationStateService publicationStateService;
  private final FactPublicationTransaction publicationTransaction;
  private final FactTaskExecutionGuard executionGuard;
  private final FactBuildTaskService taskService;
  private final SyncRunEventRecorder eventRecorder;
  private final GitlabMirrorProperties mirrorProperties;
  private final ReadPublicationService readPublicationService;
  private final ReadPublicationRepository readPublicationRepository;
  private final CustomerIssueMilestoneCatalogReconciliationService catalogReconciliationService;
  private final com.data.collection.platform.service.read.CandidateProjectionArtifactService projectionArtifacts;

  public FactTargetPublicationService(
      JdbcTemplate jdbcTemplate,
      FactProjectionScopeResolver scopeResolver,
      FactProjectionGenerationService generationService,
      com.data.collection.platform.service.sync.SyncRunPublicationFenceService
          publicationFenceService,
      SyncFactPublicationStateService publicationStateService,
      FactPublicationTransaction publicationTransaction,
      FactTaskExecutionGuard executionGuard,
      FactBuildTaskService taskService,
      SyncRunEventRecorder eventRecorder,
      GitlabMirrorProperties mirrorProperties,
      ReadPublicationService readPublicationService,
      ReadPublicationRepository readPublicationRepository,
      CustomerIssueMilestoneCatalogReconciliationService catalogReconciliationService,
      com.data.collection.platform.service.read.CandidateProjectionArtifactService projectionArtifacts) {
    this.jdbcTemplate = jdbcTemplate;
    this.scopeResolver = scopeResolver;
    this.generationService = generationService;
    this.publicationFenceService = publicationFenceService;
    this.publicationStateService = publicationStateService;
    this.publicationTransaction = publicationTransaction;
    this.executionGuard = executionGuard;
    this.taskService = taskService;
    this.eventRecorder = eventRecorder;
    this.mirrorProperties = mirrorProperties;
    this.readPublicationService = readPublicationService;
    this.readPublicationRepository = readPublicationRepository;
    this.catalogReconciliationService = catalogReconciliationService;
    this.projectionArtifacts = projectionArtifacts;
  }

  /** 定向（增量）事实构建动作：在候选修订身份内替换给定根的投影。 */
  @FunctionalInterface
  public interface FactRevisionAction {
    FactBuildResponse apply(FactRevisionTarget target, List<Long> rootIds);
  }

  /** 全量事实构建动作；构建内部以分批事务提交，批间通过进度回调续租并上报进度。 */
  @FunctionalInterface
  public interface FullFactRevisionAction {
    FactBuildResponse build(FactRevisionTarget target, FactBuildProgress progress);
  }

  /**
   * 在稳定根版本锁内构建固定输入的一个批次，共享本运行的同域候选。
   *
   * <p>事实 DML、根清单及批次完成原子提交；整个捕获集合完成后进入 PROJECTING。
   * 本方法不切换公开指针，不结算 heads；失败由工作器在回滚后中止整个候选。
   */
  public FactBuildResponse publish(QueuedFactBuildTask task, FactRevisionAction action) {
    if (task == null || task.id() == null || task.leaseOwner() == null) {
      throw new IllegalArgumentException("事实发布需要已领取且带 owner 的任务");
    }
    ReadPublication candidate = publicationTransaction.execute(() -> openCandidate(task,
        ReadPublicationDomainKey.gitlabFact(task.sourceInstance(), FactType.valueOf(task.factType())), null, 0L));
    try {
      return publicationTransaction.execute(() -> buildBatch(task, action, candidate));
    } catch (FactCandidateBusyException error) {
      throw error;
    } catch (RuntimeException error) {
      abortPreservingFailure(candidate.id(), error, () -> abortTaskCandidate(task, error.getMessage()));
      throw error;
    }
  }

  /** 无需来源副作用的收口：旧增量批次已被当前完整版本覆盖时，以父子租约校验原子完成并释放归属。 */
  @Transactional
  public FactBuildResponse completeCoveredTask(QueuedFactBuildTask task) {
    executionGuard.requireAuthorization(task);
    ReadPublicationDomainKey key = ReadPublicationDomainKey.gitlabFact(task.sourceInstance(), FactType.valueOf(task.factType()));
    ReadPublicationDomain domain = readPublicationRepository.findDomain(key).orElse(null);
    if (domain == null || domain.currentPublicationId() == null || taskService.hasUnpublishedOwnedRoots(task)) {
      throw new FactCandidateBusyException("当前批次尚未被完整版本覆盖");
    }
    readPublicationRepository.lockDomain(domain.id());
    FactBuildResponse response = new FactBuildResponse(task.scope(), false, 0, "事实目标已由当前完整版本覆盖");
    finishOwnedTask(task, response);
    taskService.releaseTaskRoots(task.id());
    return response;
  }

  private FactBuildResponse buildBatch(QueuedFactBuildTask task, FactRevisionAction action,
      ReadPublication candidate) {
    readPublicationRepository.lockDomain(candidate.domainId());
    ReadPublication locked = readPublicationRepository.lockPublication(candidate.id()).orElseThrow();
    if (locked.state() != com.data.collection.platform.entity.read.ReadPublicationState.BUILDING) {
      throw new FactCandidateBusyException("该候选已经进入投影或终态，当前批次让位");
    }
    FactType factType = FactType.valueOf(task.factType());
    PublicationFactTable table = PublicationFactTable.of(factType);
    Map<Long, Long> versions = readPublicationRepository.lockTaskInputVersions(candidate.id(), task.id());
    List<Long> rootIds = List.copyOf(versions.keySet());
    if (rootIds.isEmpty()) {
      FactBuildResponse noop = new FactBuildResponse(task.scope(), false, 0, "本批根不属于捕获输入或已完成");
      finishOwnedTask(task, noop);
      taskService.releaseTaskRoots(task.id());
      if (!readPublicationRepository.hasUnbuiltInputs(candidate.id())) {
        requireProjections(candidate, Set.of());
        prepareProjectionPhase(candidate);
      }
      return noop;
    }
    readPublicationRepository.requireUnchangedDefinitions(candidate, task.sourceInstance());
    FactRevisionTarget target = new FactRevisionTarget(candidate.id(), candidate.epochId(), table,
        SOURCE_SYSTEM_GITLAB, task.sourceInstance(), candidate.coveredChangeVersion());
    Set<FactProjectionScope> scopes = new LinkedHashSet<>(
        scopeResolver.resolveCurrentScopes(task.sourceInstance(), factType, rootIds));
    FactBuildResponse response = action.apply(target, rootIds);
    readPublicationRepository.requireUnchangedDefinitions(candidate, task.sourceInstance());
    scopes.addAll(scopeResolver.resolveCandidateScopes(task.sourceInstance(), factType, rootIds, candidate.id()));
    readPublicationRepository.registerUpsertManifest(table, candidate.id(), candidate.coveredChangeVersion());
    readPublicationRepository.applyRootCapturedVersions(candidate.id(), table, versions);
    readPublicationRepository.completeRootInputs(candidate.id(), task.id(), rootIds);
    requireProjections(candidate, scopes);
    finishOwnedTask(task, response);
    if (!readPublicationRepository.hasUnbuiltInputs(candidate.id())) {
      prepareProjectionPhase(candidate);
    }
    return response;
  }

  /**
   * 以新代际分批构建全量候选，稳定输入核验成功后进入 PROJECTING。
   * 构建或准备失败中止候选并清理已提交批次；公开指针、heads 与全量意图留待统一发布。
   */
  public FactBuildResponse publishFull(QueuedFactBuildTask task, FullFactRevisionAction action) {
    if (task == null || task.id() == null || task.leaseOwner() == null || !task.full()) {
      throw new IllegalArgumentException("全量事实发布需要已领取的全量任务");
    }
    executionGuard.requireCurrentTaskAuthorization();
    FactType factType = FactType.valueOf(task.factType());
    PublicationFactTable table = PublicationFactTable.ofFactType(task.factType());
    ReadPublication candidate = publicationTransaction.execute(() -> openCandidate(task,
        ReadPublicationDomainKey.gitlabFact(task.sourceInstance(), factType), UUID.randomUUID(), 0L));
    FactRevisionTarget target = new FactRevisionTarget(candidate.id(), candidate.epochId(), table,
        SOURCE_SYSTEM_GITLAB, task.sourceInstance(), candidate.coveredChangeVersion());
    try {
      FactBuildResponse response = action.build(target, progressReporter(task));
      publicationTransaction.execute(() -> {
        readPublicationRepository.lockDomain(candidate.domainId());
        readPublicationRepository.requireUnchangedDefinitions(candidate, task.sourceInstance());
        readPublicationRepository.completeFullRootInputs(candidate.id(), task.id());
        if (publicationStateService.publicationUpperBound(task.sourceInstance(), factType)
            > candidate.coveredChangeVersion()) {
          throw new IllegalStateException("全量构建期间来源新增变化，必须整体重建");
        }
        readPublicationRepository.registerUpsertManifest(table, candidate.id(), candidate.coveredChangeVersion());
        requireProjections(candidate, Set.of(new FactProjectionScope(task.sourceInstance(), factType,
            ProjectionScopeType.FULL_EPOCH, FactProjectionScopeKeyCodec.SINGLETON_SCOPE_KEY)));
        finishOwnedTask(task, response);
        prepareProjectionPhase(candidate);
        return null;
      });
      eventRecorder.record(task.factRunId(), task.configId(), task.sourceInstance(),
          "FACT_BUILD_COMPLETED", "全量候选事实构建完成，等待必需投影（" + response.affectedRows() + " 行）");
      return response;
    } catch (RuntimeException error) {
      abortPreservingFailure(candidate.id(), error, () -> readPublicationService.abortOwnedTaskCandidate(
          candidate.id(), task.id(), task.leaseOwner(), String.valueOf(task.factRunId()),
          task.factRunLeaseToken(), "全量候选构建/准备失败：" + error.getMessage()));
      throw error;
    }
  }

  /**
   * 旁路生产者在自有候选内写入修订并进入 PROJECTING，等待必需投影完成。
   *
   * <p>用于不经过变化头的生产者（延期重算、正式记录导入、外部指标补齐、单条手工刷新）：它们在自己的
   * 候选代际内写入修订；本方法只完成准备，旧版本继续可读。投影成功后调用 publishPreparedCandidate，
   * 旁路不结算变化头，因为它们不消费待发布的变化水位。
   *
   * <p>来源完整代际证明由生产者提供：GitLab 事实族取自同步控制面，兼容评审等独立生产者必须自带自身来源的
   * 证据，发布层不猜测。域尚无已认证完整基线时，读发布服务会拒绝开启增量候选。
   *
   * @param request 旁路发布输入（域、来源身份与证明、覆盖水位、审计与数据截止）
   * @param body 在候选修订身份内执行的写入
   * @return 写入结果
   */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public FactBuildResponse publishWithinCandidate(
      BypassPublicationRequest request,
      Function<FactRevisionTarget, FactBuildResponse> body) {
    ReadPublicationDomainKey domainKey = request.domainKey();
    readPublicationRepository.requireStableBypassInputs(domainKey);
    if (domainKey.producerKind() == com.data.collection.platform.entity.read.ReadPublicationProducerKind.GITLAB_FACT
        && !publicationStateService.canBuildReadCandidate(domainKey.sourceInstance(), FactType.valueOf(domainKey.factType()))) {
      throw new FactCandidateBusyException("事实来源缺少完整输入证据，旁路生产者让位：" + domainKey);
    }
    PublicationFactTable table = PublicationFactTable.ofFactType(domainKey.factType());
    UUID domainId = readPublicationRepository.ensureDomain(UUID.randomUUID(), domainKey);
    ReadPublicationDomain domain = readPublicationRepository.lockDomain(domainId);
    if (readPublicationRepository.findActiveCandidate(domainId).isPresent()) {
      throw new FactCandidateBusyException("该域已有活动候选，旁路生产者让位：" + domainKey);
    }
    UUID definitionId = readPublicationRepository.freezeCurrentFactDefinitions(domainKey.sourceInstance());
    if (!request.fullDataset()) {
      readPublicationRepository.requireReusableCalculationDefinitions(domain.currentPublicationId(), definitionId, domainKey);
    }
    ReadPublication candidate = readPublicationService.openCandidate(new CandidateOpenRequest(
        domainKey, request.fullDataset() ? UUID.randomUUID() : null, domain.currentPublicationId(), request.sourceSealJson(),
        request.coveredChangeVersion(), definitionId, request.producerRunId(), request.producerTaskId(),
        null, request.dataAsOf()));
    FactRevisionTarget target =
        new FactRevisionTarget(
            candidate.id(),
            candidate.epochId(),
            table,
            request.sourceSystem(),
            domainKey.sourceInstance(),
            request.coveredChangeVersion());
    FactBuildResponse response = body.apply(target);
    // 旁路也必须给出完整根清单：UPSERT 身份取自真实候选修订行，删除证明由持久化协作者按完整身份登记；
    // 缺少清单时同根更新会撞开放修订唯一索引，来源删除则会发布成"旧根仍可见的成功"。
    readPublicationRepository.registerUpsertManifest(table, candidate.id(), request.coveredChangeVersion());
    readPublicationRepository.requireUnchangedDefinitions(candidate, domainKey.sourceInstance());
    List<Long> roots = jdbcTemplate.queryForList("select distinct root_id from read_publication_fact_roots where publication_id = ?", Long.class, candidate.id());
    requireProjections(candidate, scopeResolver.resolveCandidateScopes(domainKey.sourceInstance(),
        FactType.valueOf(domainKey.factType()), roots, candidate.id()));
    prepareProjectionPhase(candidate);
    return response;
  }

  private ReadPublication openCandidate(
      QueuedFactBuildTask task,
      ReadPublicationDomainKey domainKey,
      UUID epochId,
      long coveredUpperBound) {
    if (!publicationStateService.canBuildReadCandidate(task.sourceInstance(), FactType.valueOf(task.factType()))) {
      throw new FactCandidateBusyException("事实来源缺少完整输入证据，构建任务让位：" + domainKey);
    }
    UUID domainId = readPublicationRepository.ensureDomain(UUID.randomUUID(), domainKey);
    ReadPublicationDomain domain = readPublicationRepository.lockDomain(domainId);
    ReadPublication candidate = readPublicationRepository.findActiveCandidate(domainId).orElse(null);
    String runId = String.valueOf(task.factRunId());
    if (candidate != null && task.full() && runId.equals(candidate.producerRunId())
        && task.id().equals(candidate.producerTaskId())
        && candidate.state() == com.data.collection.platform.entity.read.ReadPublicationState.BUILDING) {
      // 同一全量意图的新合法持有者重新构建：已提交半成品不能拼入重试的新输入。
      readPublicationService.abort(candidate.id(), "全量任务重新取得执行权，清理上一尝试的未公开修订");
      candidate = null;
    }
    if (candidate != null) {
      boolean fullCandidate = candidate.producerTaskId() == null || Boolean.TRUE.equals(jdbcTemplate.queryForObject(
          "select full_build from fact_build_tasks where id = ?", Boolean.class, candidate.producerTaskId()));
      if (!runId.equals(candidate.producerRunId()) || task.full() != fullCandidate
          || candidate.state() != com.data.collection.platform.entity.read.ReadPublicationState.BUILDING
          || task.full()) {
        throw new FactCandidateBusyException("该域已有活动候选，后来运行让位：" + domainKey);
      }
    } else {
      UUID definitionId = readPublicationRepository.freezeCurrentFactDefinitions(task.sourceInstance());
      if (!task.full()) {
        readPublicationRepository.requireReusableCalculationDefinitions(domain.currentPublicationId(), definitionId, domainKey);
      }
      candidate = readPublicationService.openCandidate(new CandidateOpenRequest(domainKey,
          epochId, domain.currentPublicationId(), publicationStateService.sourceSeal(task.sourceInstance(),
          FactType.valueOf(task.factType()), coveredUpperBound), coveredUpperBound, definitionId,
          runId, task.id(), runId, Instant.now()));
      long capturedUpper = readPublicationRepository.captureRootInputs(candidate.id(), task.sourceInstance(), task.factType(), task.full());
      jdbcTemplate.update("update read_publications set source_seal = ?::jsonb where id = ?",
          publicationStateService.sourceSeal(task.sourceInstance(), FactType.valueOf(task.factType()), capturedUpper), candidate.id());
      if (task.full()) {
        readPublicationRepository.updateCapturedFullRequestRevision(candidate.id(),
            publicationStateService.currentFullRequestRevision(task.sourceInstance(), FactType.valueOf(task.factType())));
      }
      candidate = readPublicationRepository.findPublication(candidate.id()).orElseThrow();
    }
    int bound = jdbcTemplate.update("""
        update fact_build_tasks set publication_id = ?, updated_at = current_timestamp
         where id = ? and status = 'RUNNING' and lock_owner = ?
        """, candidate.id(), task.id(), task.leaseOwner());
    if (bound != 1) {
      throw new FactTaskLeaseLostException(task.id(), "不能绑定候选");
    }
    return candidate;
  }

  private void prepareProjectionPhase(ReadPublication candidate) {
    ReadPublicationDomainKey key = readPublicationRepository.findDomain(candidate.domainId()).orElseThrow().key();
    readPublicationRepository.requireUnchangedDefinitions(candidate, key.sourceInstance());
    if (key.factType().equals(FactType.ISSUE.name())) {
      int added = catalogReconciliationService.reconcileFactValues(readPublicationService.candidateContext(candidate.id()));
      if (added > 0) {
        UUID definition = readPublicationRepository.freezeCurrentFactDefinitions(key.sourceInstance());
        jdbcTemplate.update("update read_publications set definition_snapshot_id = ? where id = ? and state = 'BUILDING'", definition, candidate.id());
        // 自动补齐的里程碑成员属于本候选定义；旧版本仍携带此前冻结定义，不会看到新候选范围。
        jdbcTemplate.query("""
            select g.id from issue_scope_groups g join issue_scope_catalogs c on c.id = g.catalog_id
             where c.project_id = ? and c.dimension = 'MILESTONE' and c.enabled and g.enabled order by g.id
            """, rs -> {
              readPublicationRepository.requireProjection(candidate.id(), key.sourceInstance(), key.factType(),
                  ProjectionScopeType.ISSUE_SCOPE_GROUP, FactProjectionScopeKeyCodec.issueScopeGroup(
                      IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID, IssueScopeDimension.MILESTONE, rs.getLong("id")));
            }, IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID);
      }
    }
    readPublicationService.markProjecting(candidate.id(), candidate.producerLeaseOwner());
  }

  private void requireProjections(ReadPublication candidate, Set<FactProjectionScope> scopes) {
    ReadPublicationDomainKey key = readPublicationRepository.findDomain(candidate.domainId()).orElseThrow().key();
    readPublicationRepository.requireProjection(candidate.id(), key.sourceInstance(), key.factType(),
        ProjectionScopeType.GLOBAL_VIEW, FactProjectionScopeKeyCodec.SINGLETON_SCOPE_KEY);
    for (FactProjectionScope scope : scopes) {
      readPublicationRepository.requireProjection(candidate.id(), key.sourceInstance(), key.factType(),
          scope.scopeType(), scope.scopeKey());
    }
  }

  /**
   * P3 完成必需投影后唯一的发布入口：按父运行租约校验，事实/指针/逐根 heads/全量意图/栅栏同事务提交。
   * 缺少任何投影证明或捕获根时拒绝；更高镜像变化不被提前结算。旁路不消费 heads。
   *
   * @param publicationId 已准备候选
   * @param runLeaseToken 父 FACT_REFRESH 当前租约；无父运行的旁路传 null
   * @return 新完整发布身份
   */
  public ReadPublication publishPreparedCandidate(UUID publicationId, String runLeaseToken) {
    ReadPublication ready = readPublicationRepository.findPublication(publicationId).orElseThrow();
    if (ready.state() == com.data.collection.platform.entity.read.ReadPublicationState.PUBLISHED) {
      return ready;
    }
    if (readPublicationRepository.hasUnbuiltInputs(publicationId)) {
      throw new IllegalStateException("捕获输入尚未全部完成，禁止发布子集：" + publicationId);
    }
    if (ready.state() != com.data.collection.platform.entity.read.ReadPublicationState.PROJECTING
        && ready.state() != com.data.collection.platform.entity.read.ReadPublicationState.READY_TO_PUBLISH) {
      throw new IllegalStateException("候选事实尚未完成准备，不能发布：" + publicationId);
    }
    readPublicationRepository.requireProjectionProofs(ready);
    try {
      return publicationTransaction.commitCandidate(() -> publishPreparedInternal(publicationId, runLeaseToken));
    } catch (FactTaskLeaseLostException error) {
      throw error;
    } catch (RuntimeException error) {
      abortPreservingFailure(publicationId, error,
          () -> readPublicationService.abort(publicationId, "统一发布失败：" + error.getMessage()));
      throw error;
    }
  }

  private void abortPreservingFailure(UUID publicationId, RuntimeException original, Runnable abort) {
    LOGGER.error("候选构建或发布失败，publicationId={}，开始逻辑中止", publicationId, original);
    try {
      abort.run();
    } catch (RuntimeException cleanupFailure) {
      if (cleanupFailure != original) {
        original.addSuppressed(cleanupFailure);
      }
      LOGGER.warn("候选逻辑中止未完成，保留原始异常并由既有巡检恢复，publicationId={}",
          publicationId, cleanupFailure);
    }
  }

  private ReadPublication publishPreparedInternal(UUID publicationId, String runLeaseToken) {
    ReadPublication initial = readPublicationRepository.findPublication(publicationId).orElseThrow();
    if (initial.producerTaskId() != null) {
      executionGuard.requireParentRunAuthorization(Long.parseLong(initial.producerRunId()), runLeaseToken);
    }
    readPublicationRepository.lockDomain(initial.domainId());
    ReadPublication candidate = readPublicationRepository.lockPublication(publicationId).orElseThrow();
    if (readPublicationRepository.hasUnbuiltInputs(publicationId)) {
      throw new IllegalStateException("捕获输入尚未全部完成，禁止发布子集：" + publicationId);
    }
    ReadPublicationDomainKey key = readPublicationRepository.findDomain(candidate.domainId()).orElseThrow().key();
    readPublicationRepository.requireUnchangedDefinitions(candidate, key.sourceInstance());
    projectionArtifacts.verifyForPublication(publicationId);
    if (candidate.state() == com.data.collection.platform.entity.read.ReadPublicationState.PROJECTING) {
      readPublicationService.markReadyToPublish(publicationId, candidate.producerLeaseOwner());
    }
    ReadPublication published = readPublicationService.publish(publicationId, candidate.producerLeaseOwner());
    jdbcTemplate.update("update statistic_board_snapshots set status = 'READY' where publication_id = ? and status = 'PREPARED'",
        publicationId);
    jdbcTemplate.update("update page_record_snapshots set status = 'READY' where publication_id = ? and status = 'PREPARED'",
        publicationId);
    PublicationFactTable table = PublicationFactTable.ofFactType(key.factType());
    readPublicationRepository.linkManifestPreviousRevisions(table, publicationId);
    if (initial.producerTaskId() != null) {
      readPublicationRepository.settleCapturedInputs(publicationId);
      Boolean full = jdbcTemplate.queryForObject("select full_build from fact_build_tasks where id = ?",
          Boolean.class, initial.producerTaskId());
      if (Boolean.TRUE.equals(full)) {
        publicationStateService.settleFullPublicationIntent(key.sourceInstance(), FactType.valueOf(key.factType()),
            readPublicationRepository.findCapturedFullRequestRevision(publicationId));
      }
    }
    // 当前 generation 只在完整发布事务中推进；预热在候选身份上完成，P3 不得读取当前事实替代候选。
    generationService.commitPreparedGenerations(published, key);
    if (initial.producerTaskId() != null) {
      // 栅栏核验消费的是已经公开的范围代际，必须在 heads/意图及 generation 都提交到本事务后再计算。
      publicationFenceService.advanceAfterFactPublication(key.sourceInstance(), FactType.valueOf(key.factType()));
    }
    readPublicationRepository.releaseCandidateTaskRoots(publicationId);
    readPublicationRepository.releaseCapturedInputs(publicationId);
    readPublicationRepository.releaseProjectionDependencies(publicationId);
    return published;
  }

  /** 父运行持有当前令牌时发布所有事实和投影均完成的候选，未就绪候选继续保留旧指针。 */
  public int publishPreparedCandidates(long factRunId, String runLeaseToken) {
    java.util.List<UUID> ids = jdbcTemplate.queryForList("""
        select p.id from read_publications p where p.producer_run_id = ? and p.producer_task_id is not null
          and p.state in ('PROJECTING', 'READY_TO_PUBLISH')
          and not exists(select 1 from read_publication_scope_generations g
            where g.publication_id = p.id and g.prepared_at is null) order by p.id
        """, UUID.class, String.valueOf(factRunId));
    for (UUID id : ids) { publishPreparedCandidate(id, runLeaseToken); }
    return ids.size();
  }

  /** 有界发布独立生产者的完整候选；与 FACT_REFRESH 使用相同校验、缓存提升和原子指针入口。 */
  public int publishPreparedStandaloneCandidates() {
    List<UUID> ids = jdbcTemplate.queryForList("""
        select p.id from read_publications p join read_publication_domains domain on domain.id = p.domain_id
         where p.producer_task_id is null and domain.fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')
          and p.state in ('PROJECTING', 'READY_TO_PUBLISH')
          and not exists(select 1 from read_publication_scope_generations g
            where g.publication_id = p.id and g.prepared_at is null)
          order by p.id limit 1
        """, UUID.class);
    for (UUID id : ids) {
      publishPreparedCandidate(id, null);
    }
    return ids.size();
  }

  /** 独立投影耗尽重试或被明确停止后释放域名额；旧公开指针继续可读，错误任务保留供管理员诊断。 */
  @Transactional
  public int recoverFailedStandaloneCandidates() {
    List<UUID> ids = jdbcTemplate.queryForList("""
        select p.id from read_publications p where p.producer_task_id is null
          and p.state = 'PROJECTING' and exists(select 1 from fact_projection_refresh_tasks t
            where t.publication_id = p.id and t.status in ('FAILED', 'PAUSED', 'SKIPPED'))
          order by p.id limit 1
        """, UUID.class);
    int recovered = 0;
    for (UUID id : ids) {
      ReadPublication initial = readPublicationRepository.findPublication(id).orElseThrow();
      readPublicationRepository.lockDomain(initial.domainId());
      readPublicationRepository.lockPublication(id).orElseThrow();
      if (!jdbcTemplate.queryForList("""
          select id from fact_projection_refresh_tasks where publication_id = ?
            and status in ('FAILED', 'PAUSED', 'SKIPPED') for update skip locked
          """, Long.class, id).isEmpty()
          && readPublicationService.abort(id, "独立投影失败或停止，保留上一完整版本")) {
        recovered++;
      }
    }
    return recovered;
  }

  /** 失败批次回滚后中止所属候选；旧持有者不得中止已由他人接管的在途任务。 */
  @Transactional
  public void abortTaskCandidate(QueuedFactBuildTask task, String reason) {
    List<UUID> ids = jdbcTemplate.queryForList("""
        select publication_id from fact_build_tasks where id = ? and publication_id is not null
          and (lock_owner = ? or status <> 'RUNNING')
        """, UUID.class, task.id(), task.leaseOwner());
    for (UUID id : ids) {
      readPublicationService.abortOwnedTaskCandidate(id, task.id(), task.leaseOwner(),
          String.valueOf(task.factRunId()), task.factRunLeaseToken(), reason);
    }
  }

  /** 巡检只释放已终态父运行或明确人工停止的候选；暂停/等待投影的运行继续持有候选。 */
  @Transactional
  public int recoverAbandonedCandidates() {
    List<UUID> ids = jdbcTemplate.queryForList("""
        select p.id from read_publications p join fact_build_tasks t on t.id = p.producer_task_id
          left join sync_runs r on r.id::text = p.producer_run_id
         where p.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')
           and (r.id is null or r.status in ('CANCELLED', 'FAILED', 'SUCCESS', 'PARTIAL_SUCCESS')
             or t.status in ('FAILED', 'PAUSED', 'SKIPPED'))
        """, UUID.class);
    int recovered = 0;
    for (UUID id : ids) {
      ReadPublication publication = readPublicationRepository.findPublication(id).orElseThrow();
      readPublicationRepository.lockDomain(publication.domainId());
      if (readPublicationService.abort(id, "父运行终态或事实意图停止，释放未公开候选")) {
        recovered++;
      }
    }
    return recovered;
  }

  /**
   * 定向人工意图未覆盖整域捕获集合时，释放未闭合候选并显式返回未完成根数量。
   * 不扩大用户选择的任务范围，也不缩小水位发布半成品；后续普通来源刷新重新完成整体版本。
   */
  @Transactional
  public long abortIncompleteIntentCandidates(long factRunId, String runLeaseToken) {
    executionGuard.requireParentRunAuthorization(factRunId, runLeaseToken);
    List<UUID> ids = jdbcTemplate.queryForList("select id from read_publications"
        + " where producer_run_id = ? and state = 'BUILDING' order by id", UUID.class, String.valueOf(factRunId));
    long remaining = 0;
    for (UUID id : ids) {
      ReadPublication initial = readPublicationRepository.findPublication(id).orElseThrow();
      readPublicationRepository.lockDomain(initial.domainId());
      long count = readPublicationRepository.countUnbuiltInputs(id);
      if (count > 0 && readPublicationService.abort(id, "定向人工意图未覆盖固定集合，未完成根 " + count)) {
        remaining += count;
      }
    }
    return remaining;
  }

  /** 返回运行活动候选及未完成根，防止把构建任务 SUCCESS 上报为完整发布 SUCCESS。 */
  public String pendingCandidateReason(long factRunId) {
    List<String> pending = jdbcTemplate.queryForList("""
        select '候选 ' || p.id::text || '：' || p.state || '，未完成根 ' ||
          (select count(*) from read_publication_root_inputs i where i.publication_id = p.id and i.built_at is null)::text
          from read_publications p where p.producer_run_id = ?
            and p.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH') order by p.id
        """, String.class, String.valueOf(factRunId));
    return pending.isEmpty() ? null : String.join("；", pending);
  }

  private FactBuildProgress progressReporter(QueuedFactBuildTask task) {
    int leaseSeconds = Math.max(1, mirrorProperties.getHeartbeatTimeoutSeconds());
    return (completedChunks, totalChunks, processedRows) -> {
      executionGuard.requireCurrentTaskAuthorization();
      if (!taskService.renewTaskLease(task, leaseSeconds)) {
        throw new IllegalStateException("事实任务租约已失效：" + task.id());
      }
      eventRecorder.record(
          task.factRunId(),
          task.configId(),
          task.sourceInstance(),
          "FACT_BUILD_PROGRESS",
          "全量事实构建进度 "
              + completedChunks
              + "/"
              + totalChunks
              + " 批次（"
              + processedRows
              + " 行）");
    };
  }

  private void finishOwnedTask(QueuedFactBuildTask task, FactBuildResponse response) {
    int updated =
        jdbcTemplate.update(
            """
            update fact_build_tasks
               set status = 'SUCCESS',
                   affected_rows = ?,
                   message = ?,
                   error_message = null,
                   lock_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where id = ? and status = 'RUNNING' and lock_owner = ?
            """,
            response.affectedRows(),
            "候选事实已构建，等待统一发布；" + response.message(),
            task.id(),
            task.leaseOwner());
    if (updated != 1) {
      throw new IllegalStateException("事实任务租约已失效：" + task.id());
    }
  }

}
