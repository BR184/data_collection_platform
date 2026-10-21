package com.data.collection.platform.service.read;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.read.CandidateReadContext;
import com.data.collection.platform.entity.read.FactRootEntry;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomain;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读发布的候选生产与原子发布。
 *
 * <p>候选在其域内独占活动名额；发布只在一个事务内移动域指针、关闭受影响旧修订、打开新修订并结算范围 generation，
 * 任何一步失败全部回滚，指针不移动、候选逻辑不可见。旧完整版本不会因下一次同步启动而失去认证。
 *
 * <p>本服务只负责读版本生命周期；生产资格（来源是否允许产生事实副作用）仍由 `SyncFactPublicationStateService`
 * 管理，两者不互相替代。
 */
@Service
public class ReadPublicationService {
  private final ReadPublicationRepository repository;
  private final ReadFallbackProperties properties;

  public ReadPublicationService(
      ReadPublicationRepository repository, ReadFallbackProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  // ---- 候选生产 ----

  /**
   * 在同一域内开启候选；已有活动候选或基线已被前移时拒绝，生产者按既有恢复规则重做。
   *
   * @param request 候选输入
   * @return 新建候选发布身份
   */
  @Transactional
  public ReadPublication openCandidate(CandidateOpenRequest request) {
    return insertCandidateInternal(request);
  }

  /** 候选进入投影阶段。 */
  @Transactional
  public ReadPublication markProjecting(UUID publicationId, String leaseOwner) {
    if (repository.hasUnbuiltInputs(publicationId)) {
      throw new IllegalStateException("捕获输入尚未全部完成，不能进入投影：" + publicationId);
    }
    return advance(publicationId, leaseOwner, ReadPublicationState.BUILDING, ReadPublicationState.PROJECTING);
  }

  /** 必需投影成功、事实关系一致后，候选具备发布条件。 */
  @Transactional
  public ReadPublication markReadyToPublish(UUID publicationId, String leaseOwner) {
    if (repository.hasUnbuiltInputs(publicationId)) {
      throw new IllegalStateException("捕获输入尚未全部完成，禁止发布子集：" + publicationId);
    }
    ReadPublication ready = lockOwnedCandidate(publicationId, leaseOwner);
    repository.requireProjectionProofs(ready);
    if (repository.findDomain(ready.domainId()).orElseThrow().key().factType().equals("DATASET")) {
      repository.requireDatasetCaptureProof(ready);
    }
    return advance(
        publicationId, leaseOwner, ReadPublicationState.PROJECTING, ReadPublicationState.READY_TO_PUBLISH);
  }

  private ReadPublication advance(
      UUID publicationId, String leaseOwner, ReadPublicationState expected, ReadPublicationState next) {
    ReadPublication publication = lockOwnedCandidate(publicationId, leaseOwner);
    if (publication.state() != expected) {
      throw new IllegalStateException(
          "候选状态不是期望的 " + expected + "：" + publicationId + " 实际 " + publication.state());
    }
    int updated = repository.transitionState(publicationId, expected, next);
    if (updated != 1) {
      throw new IllegalStateException("候选状态迁移失败（可能已被其它生产者推进）：" + publicationId);
    }
    return requirePublication(publicationId);
  }

  /** 登记候选根清单（生产者分批调用；条目必须属于候选域，重复登记幂等且不回退水位）。 */
  @Transactional
  public int recordFactRoot(FactRootEntry entry) {
    ReadPublication publication = lockOwnedCandidate(entry.publicationId(), null);
    if (!publication.activeCandidate()) {
      throw new IllegalStateException("只有活动候选可以登记根清单：" + entry.publicationId());
    }
    ReadPublicationDomain domain = repository.findDomain(publication.domainId()).orElseThrow(
        () -> new IllegalStateException("候选所属域不存在：" + publication.domainId()));
    if (!domain.key().sourceInstance().equals(entry.sourceInstance())
        || !domain.key().factType().equals(entry.factType())) {
      throw new IllegalStateException(
          "根清单条目与候选域不一致（"
              + entry.sourceInstance()
              + "/"
              + entry.factType()
              + " 不属于 "
              + domain.key()
              + "）");
    }
    return repository.recordFactRoot(entry);
  }

  /** 登记候选范围 generation。 */
  @Transactional
  public int recordScopeGeneration(
      UUID publicationId,
      ProjectionScopeType scopeType,
      String scopeKey,
      long candidateGeneration) {
    ReadPublication publication = lockOwnedCandidate(publicationId, null);
    if (!publication.activeCandidate()) {
      throw new IllegalStateException("只有活动候选可以登记范围 generation：" + publicationId);
    }
    return repository.recordScopeGeneration(publicationId, scopeType, scopeKey, candidateGeneration);
  }

  // ---- 原子发布 ----

  /**
   * 在单个事务内原子发布候选。
   *
   * <p>依次校验候选状态与生产者租约、锁定域并校验基线 CAS、分配保留序号、关闭旧代际与受影响旧修订、
   * 打开候选新修订、提交范围 generation、置为 PUBLISHED 并推进域指针；任一步不满足即抛异常整体回滚。
   *
   * @param publicationId 候选发布身份
   * @param leaseOwner 当前生产者租约持有者；为空表示不校验
   * @return 已发布身份
   */
  @Transactional
  public ReadPublication publish(UUID publicationId, String leaseOwner) {
    return publishInternal(publicationId, leaseOwner);
  }

  private ReadPublication publishInternal(UUID publicationId, String leaseOwner) {
    ReadPublication publication = lockOwnedCandidate(publicationId, leaseOwner);
    if (publication.state() != ReadPublicationState.READY_TO_PUBLISH) {
      throw new IllegalStateException(
          "候选尚未具备发布条件（必须为 READY_TO_PUBLISH）：" + publicationId + " 实际 " + publication.state());
    }
    repository.requireProjectionProofs(publication);
    ReadPublicationDomain domain = repository.lockDomain(publication.domainId());
    if (!Objects.equals(domain.currentPublicationId(), publication.basePublicationId())) {
      throw new IllegalStateException("域基线已前移，候选必须重建：" + publicationId);
    }
    PublicationFactTable table = tryTableOf(domain.key());
    if (table == null && !domain.key().factType().equals("DATASET")) {
      throw new IllegalStateException("未知读取域内容类型：" + domain.key());
    }
    long sequence = repository.allocateSequence(domain.id());

    UUID baseEpochId =
        publication.basePublicationId() == null
            ? null
            : repository
                .findPublication(publication.basePublicationId())
                .map(ReadPublication::epochId)
                .orElse(null);
    if (table != null) {
      if (baseEpochId != null && !baseEpochId.equals(publication.epochId())) {
        repository.closeEpochOpenRevisions(table, baseEpochId, sequence);
      }
      repository.closeTouchedOpenRevisions(table, publication.epochId(), publicationId, sequence);
      repository.openCandidateRevisions(table, publicationId, sequence);
    } else {
      repository.publishDatasetRevisions(publication, publication.basePublicationId() == null ? null
          : requirePublication(publication.basePublicationId()), sequence);
    }
    repository.commitScopeGenerations(publicationId);

    int published = repository.markPublished(publicationId, sequence, Instant.now());
    if (published != 1) {
      throw new IllegalStateException("发布终态写入失败：" + publicationId);
    }
    int pointerAdvanced =
        repository.updateDomainCurrentPublication(
            domain.id(), publication.basePublicationId(), publicationId);
    if (pointerAdvanced != 1) {
      throw new IllegalStateException("域发布指针 CAS 失败（基线已被前移）：" + publicationId);
    }
    return requirePublication(publicationId);
  }

  /**
   * 中止候选：指针不移动，释放活动名额、投影和执行归属。
   *
   * <p>事实修订的物理删除由持久标识交给维护调度分批完成，不在失败恢复事务里整批删除；
   * 已中止的未公开修订不参与公开读取或后续候选。数据集中止仍沿用原捕获事务的清理契约。
   *
   * @param publicationId 候选发布身份
   * @param reason 中止原因（审计）
   * @return 是否实际中止
   */
  @Transactional
  public boolean abort(UUID publicationId, String reason) {
    ReadPublication initial = requirePublication(publicationId);
    repository.lockDomain(initial.domainId());
    ReadPublication publication = repository.lockPublication(publicationId).orElseThrow(
        () -> new IllegalStateException("候选不存在：" + publicationId));
    if (!publication.activeCandidate()) {
      return false;
    }
    ReadPublicationDomain domain = repository.findDomain(publication.domainId()).orElseThrow(
        () -> new IllegalStateException("候选所属域不存在：" + publication.domainId()));
    int updated = repository.transitionState(publicationId, publication.state(), ReadPublicationState.ABORTED);
    if (updated != 1) { throw new IllegalStateException("候选中止状态迁移失败：" + publicationId); }
    repository.recordAbortReason(publicationId, reason);
    PublicationFactTable table = tryTableOf(domain.key());
    if (table != null) {
      repository.setAbortCleanupPending(publicationId, true);
    } else if (domain.key().factType().equals("DATASET")) {
      repository.abortDatasetCapture(publicationId);
    }
    repository.deleteFactRoots(publicationId);
    repository.deleteScopeGenerations(publicationId);
    repository.deleteCandidateProjections(publicationId);
    repository.releaseCandidateTaskRoots(publicationId);
    repository.releaseCapturedInputs(publicationId);
    return true;
  }

  /**
   * 维护调度清理一个已中止事实候选的一批父修订和子关系；每次调用独立提交，可从剩余数据继续。
   *
   * @param publicationId 持久待清理身份；活动/已发布/已清理身份不处理
   * @return 本批删除的父修订数，最多为配置的 gcBatchSize（非法非正值按 1 处理）
   */
  @Transactional
  public int cleanupAbortedFactCandidate(UUID publicationId) {
    ReadPublication initial = requirePublication(publicationId);
    repository.lockDomain(initial.domainId());
    ReadPublication publication = repository.lockPublication(publicationId).orElseThrow();
    if (publication.state() != ReadPublicationState.ABORTED
        || !repository.isAbortCleanupPending(publicationId)) {
      return 0;
    }
    ReadPublicationDomain domain = repository.findDomain(publication.domainId()).orElseThrow();
    PublicationFactTable table = tryTableOf(domain.key());
    if (table == null) {
      throw new IllegalStateException("待清理身份不是事实候选：" + publicationId);
    }
    int deleted = repository.deleteCandidateRevisions(table, publicationId,
        Math.max(1, properties.getGcBatchSize()));
    if (!repository.hasUnpublishedCandidateRevisions(table, publicationId)) {
      repository.setAbortCleanupPending(publicationId, false);
    }
    return deleted;
  }

  /** 回滚后的事实工作器清理入口；域、任务、候选同事务锁定，旧任务令牌不能清理新持有者的候选。 */
  @Transactional
  public boolean abortOwnedTaskCandidate(UUID publicationId, long taskId, String taskOwner,
      String producerRunId, String runLeaseToken, String reason) {
    ReadPublication initial = requirePublication(publicationId);
    repository.lockDomain(initial.domainId());
    if (!repository.taskStillOwnsCandidate(taskId, taskOwner, publicationId, runLeaseToken)
        || !Objects.equals(initial.producerRunId(), producerRunId)) {
      return false;
    }
    return abort(publicationId, reason);
  }

  // ---- 初始版本回填 ----

  /**
   * 把既有完整事实登记为域的初始完整版本并原子激活。
   *
   * <p>安装/回填专用：先以 NOWAIT 取得稳定输入写入栅栏，再由 {@code verification} 核验可采纳性。
   * 已有生产写入或不可采纳时不写入，返回 {@code null}，由调用方继续既有展示。成功取得的栅栏保持到事务结束，
   * 普通查询不受阻；不得把本方法用于日常同步，或在接管事务中等待网络、用户操作。
   * 采纳时只置代际与来源发布身份（可见区间留空），再走与增量发布同一条发布路径分配初始序号并推进指针。
   *
   * @param key 目标域
   * @param sourceSystem 目标来源系统（业务键的一部分，如 GITLAB / LEGACY_PLATFORM）
   * @param dataAsOf 既有完整事实的数据截止时刻
   * @param sourceSealJson 冻结的来源完整代际证明
   * @param coveredChangeVersion 回填覆盖到的变化水位上界
   * @param verification 可采纳性判定
   * @return 已激活的初始版本；域已有当前版本或不可采纳时返回 {@code null}
   */
  @Transactional
  public ReadPublication bootstrapInitialVersion(
      ReadPublicationDomainKey key,
      String sourceSystem,
      Instant dataAsOf,
      String sourceSealJson,
      long coveredChangeVersion,
      InitialVersionVerification verification) {
    ReadPublicationDomain existing = repository.findDomain(key).orElse(null);
    if (existing != null && existing.currentPublicationId() != null) {
      return null;
    }
    PublicationFactTable table = fatalTableOf(key);
    if (!repository.tryLockInitialVersionInputs(table)) {
      return null;
    }
    InitialVersionVerification.VerificationResult result = verification.verify(key);
    if (!result.admissible()) {
      return null;
    }
    if (!repository.hasAdmissibleLegacyRevisions(table, sourceSystem, key.sourceInstance())) { return null; }
    UUID domainId = repository.ensureDomain(UUID.randomUUID(), key);
    ReadPublicationDomain domain = repository.lockDomain(domainId);
    if (domain.currentPublicationId() != null) {
      return null;
    }
    ReadPublication candidate =
        insertCandidateInternal(
            new CandidateOpenRequest(
                key, UUID.randomUUID(), null, sourceSealJson, coveredChangeVersion,
                repository.freezeCurrentFactDefinitions(key.sourceInstance()), null, null,
                "bootstrap", dataAsOf));
    advanceInternal(candidate.id(), ReadPublicationState.BUILDING, ReadPublicationState.PROJECTING);
    advanceInternal(
        candidate.id(), ReadPublicationState.PROJECTING, ReadPublicationState.READY_TO_PUBLISH);
    repository.adoptExistingRevisions(
        table, sourceSystem, key.sourceInstance(), candidate.epochId(), candidate.id());
    if (table == PublicationFactTable.ISSUE) {
      repository.adoptIssueCustomerMemberBindings(candidate.epochId(), candidate.id());
    }
    if (table == PublicationFactTable.MERGE_REQUEST) {
      repository.adoptMergeRequestCommitBindings(candidate.epochId(), candidate.id());
    }
    return publishInternal(candidate.id(), "bootstrap");
  }

  private ReadPublication insertCandidateInternal(CandidateOpenRequest request) {
    UUID domainId = repository.ensureDomain(UUID.randomUUID(), request.domainKey());
    ReadPublicationDomain domain = repository.lockDomain(domainId);
    if (repository.findActiveCandidate(domainId).isPresent()) {
      throw new IllegalStateException("该域已有活动候选，不能并发开启：" + request.domainKey());
    }
    if (!Objects.equals(domain.currentPublicationId(), request.basePublicationId())) {
      throw new IllegalStateException(
          "候选基线与域当前发布指针不一致，必须重新构建候选：" + request.domainKey());
    }
    // 定向/旁路增量继承基线代际：没有已认证的完整基线时，局部集合不得被发布成首个完整版本，
    // 成熟数据必须先经合格初始接管（或全量新代际）建立完整视图。
    if (request.epochId() == null && domain.currentPublicationId() == null) {
      throw new IllegalStateException(
          "该域尚无已认证的完整基线，定向/旁路写入不能认证为首个完整版本：" + request.domainKey());
    }
    ReadPublication publication = new ReadPublication(
        UUID.randomUUID(),
        domainId,
        resolveEpochId(request),
        0L,
        request.basePublicationId(),
        request.sourceSealJson(),
        request.coveredChangeVersion(),
        request.definitionSnapshotId(),
        ReadPublicationState.BUILDING,
        request.producerRunId(),
        request.producerTaskId(),
        request.producerLeaseOwner(),
        request.dataAsOf(),
        null);
    repository.insertCandidate(publication);
    return requirePublication(publication.id());
  }

  private ReadPublication advanceInternal(
      UUID publicationId, ReadPublicationState expected, ReadPublicationState next) {
    ReadPublication publication = requirePublication(publicationId);
    if (publication.state() != expected) {
      throw new IllegalStateException("候选状态不是期望的 " + expected + "：" + publicationId);
    }
    int updated = repository.transitionState(publicationId, expected, next);
    if (updated != 1) {
      throw new IllegalStateException("候选状态迁移失败：" + publicationId);
    }
    return requirePublication(publicationId);
  }

  // ---- 回收 ----

  /**
   * 域内回收：标记无引用的历史版本，并删除不再被任何保留序号覆盖的修订。
   *
   * <p>保护集合覆盖**本轮所有未获回收排他锁的版本**（在途读取占用、尚未达到回收条件、当前版本、
   * 被页面视图或活动候选基线引用的版本），因此同代际内即使有别的版本正在被回收，也不会误删未获锁版本的修订。
   * 回收资格判定后立刻取排他锁，锁后重查回收对象，不接受过期快照。删除修订前先清其精确父修订上的子关系。
   *
   * @param key 目标域
   * @return 回收结果
   */
  @Transactional
  public ReadGcResult collectGarbage(ReadPublicationDomainKey key) {
    UUID domainId = repository.findDomain(key).map(ReadPublicationDomain::id).orElse(null);
    if (domainId == null) {
      return ReadGcResult.empty();
    }
    repository.lockDomain(domainId);
    Instant graceBefore =
        Instant.now().minus(properties.getRetirementGraceMinutes(), ChronoUnit.MINUTES);
    PublicationFactTable table = tryTableOf(key);
    if (table == null && !key.factType().equals("DATASET")) {
      throw new IllegalStateException("未知读取域内容类型：" + key);
    }

    // 资格判定后立即显式排他锁，并跳过仍被读取共享锁占用的行；未获锁的版本本轮保持原状。
    List<UUID> lockedRetirable =
        repository.lockPublicationsForUpdateSkippingLocked(
            repository.listRetirablePublications(domainId, graceBefore));
    lockedRetirable = repository.listRetirablePublications(domainId, graceBefore).stream()
        .filter(new HashSet<>(lockedRetirable)::contains).toList();
    int retiring = repository.markRetiring(lockedRetirable);

    // 锁后重查可回收对象：只回收本轮真正取得排他锁的版本，避免接受过期快照。
    List<UUID> lockedReclaimable =
        repository.lockPublicationsForUpdateSkippingLocked(
            repository.listReclaimableRetiringPublications(domainId, graceBefore));
    lockedReclaimable = repository.listReclaimableRetiringPublications(domainId, graceBefore).stream()
        .filter(new HashSet<>(lockedReclaimable)::contains).toList();
    List<UUID> toReclaim = new ArrayList<>(lockedRetirable);
    toReclaim.addAll(lockedReclaimable);

    List<Long> retained = repository.retainedSequences(domainId, toReclaim);
    int deletedRevisions = 0;
    for (UUID epochId : repository.listEpochsWithRetiring(domainId)) {
      int remainingBudget = properties.getGcBatchSize() - deletedRevisions;
      if (remainingBudget <= 0) { break; }
      deletedRevisions +=
          table == null ? repository.deleteUnreferencedDatasetRows(epochId, retained, remainingBudget)
              : repository.deleteUnreferencedRevisions(table, epochId, retained, remainingBudget);
    }

    int removed = 0;
    for (UUID publicationId : lockedReclaimable) {
      // 仍被当前修订引用的创建者元数据需要保留，但没有视图/候选引用的旧结果缓存已不再服务。
      repository.deletePublicationCaches(publicationId);
      if ((table == null ? repository.countDatasetRowsCreatedBy(publicationId)
          : repository.countRevisionsCreatedBy(table, publicationId)) > 0L) {
        continue;
      }
      removed += repository.deletePublicationMeta(publicationId);
    }
    return new ReadGcResult(retiring, deletedRevisions, removed);
  }

  // ---- 读取上下文支持 ----

  /** 按发布身份构造候选读上下文（仅背景预热器使用）。 */
  public CandidateReadContext candidateContext(UUID publicationId) {
    ReadPublication publication =
        repository.findPublication(publicationId).orElseThrow(
            () -> new IllegalStateException("候选不存在：" + publicationId));
    ReadPublicationDomain domain =
        repository.findDomain(publication.domainId()).orElseThrow(
            () -> new IllegalStateException("候选所属域不存在：" + publication.domainId()));
    long baseSequence = 0L;
    UUID baseEpochId = null;
    if (publication.basePublicationId() != null) {
      ReadPublication base =
          repository.findPublication(publication.basePublicationId()).orElseThrow(
              () -> new IllegalStateException("候选基线不存在：" + publication.basePublicationId()));
      baseSequence = base.sequenceNo();
      baseEpochId = base.epochId();
    }
    return new CandidateReadContext(
        domain.key().sourceInstance(),
        fatalTableOf(domain.key()),
        publication.epochId(),
        publication.id(),
        publication.basePublicationId(),
        baseEpochId,
        baseSequence,
        publication.coveredChangeVersion());
  }

  private ReadPublication lockOwnedCandidate(UUID publicationId, String leaseOwner) {
    ReadPublication initial = requirePublication(publicationId);
    repository.lockDomain(initial.domainId());
    ReadPublication publication =
        repository.lockPublication(publicationId).orElseThrow(
            () -> new IllegalStateException("发布身份不存在：" + publicationId));
    if (leaseOwner != null
        && publication.producerLeaseOwner() != null
        && !leaseOwner.equals(publication.producerLeaseOwner())) {
      throw new IllegalStateException("生产者租约已被接管，拒绝推进候选：" + publicationId);
    }
    return publication;
  }

  private ReadPublication requirePublication(UUID publicationId) {
    return repository.findPublication(publicationId).orElseThrow(
        () -> new IllegalStateException("发布身份不存在：" + publicationId));
  }

  private UUID resolveEpochId(CandidateOpenRequest request) {
    if (request.epochId() != null) {
      return request.epochId();
    }
    if (request.basePublicationId() == null) {
      return UUID.randomUUID();
    }
    return repository
        .findPublication(request.basePublicationId())
        .map(ReadPublication::epochId)
        .orElseThrow(
            () -> new IllegalStateException("候选基线不存在：" + request.basePublicationId()));
  }

  private static PublicationFactTable fatalTableOf(ReadPublicationDomainKey key) {
    PublicationFactTable table = tryTableOf(key);
    if (table == null) {
      throw new IllegalStateException("该域不是主事实域，暂不支持读发布：" + key);
    }
    return table;
  }

  private static PublicationFactTable tryTableOf(ReadPublicationDomainKey key) {
    try {
      return PublicationFactTable.ofFactType(key.factType());
    } catch (IllegalArgumentException error) {
      return null;
    }
  }
}
