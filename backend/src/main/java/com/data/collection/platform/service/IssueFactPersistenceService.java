package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以同一事务在候选修订身份内写入议题事实及其客户成员关系。
 *
 * <p>P2 起事实不再原地 upsert：每次构建把业务内容写成候选代际内的新修订（物理 {@code id} 即修订身份），
 * 父子关系绑定到该修订；来源已消失的根只登记删除证明，由发布会话关闭其旧修订区间。已发布修订不被改写，
 * 因此旧完整版本在多个发布序号上保持可读。
 */
@Service
public class IssueFactPersistenceService {
  private static final int BATCH_SIZE = 200;

  private final IssueFactMapper issueFactMapper;
  private final IssueFactCustomerMembershipRepository customerMembershipRepository;
  private final ReadPublicationRepository publicationRepository;

  public IssueFactPersistenceService(
      IssueFactMapper issueFactMapper,
      IssueFactCustomerMembershipRepository customerMembershipRepository,
      ReadPublicationRepository publicationRepository) {
    this.issueFactMapper = issueFactMapper;
    this.customerMembershipRepository = customerMembershipRepository;
    this.publicationRepository = publicationRepository;
  }

  /**
   * 在候选内批量写入议题事实修订与客户成员；同一候选重试幂等覆盖同一修订行。
   *
   * @param target 候选修订写入目标
   * @param facts 同一事实构建批次的议题事实
   */
  @Transactional
  public void upsertIssueFacts(FactRevisionTarget target, List<IssueFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    publicationRepository.requireWritableTarget(target);
    for (int offset = 0; offset < facts.size(); offset += BATCH_SIZE) {
      List<IssueFact> batch = facts.subList(offset, Math.min(offset + BATCH_SIZE, facts.size()));
      issueFactMapper.batchUpsert(batch, target.epochId(), target.publicationId());
      customerMembershipRepository.replaceForFacts(target, batch);
    }
  }

  /** 延期重算只复制原修订并替换三列判定；全部其它业务列与成员集合原样保留，旧修订不改。 */
  @Transactional
  public void copyDelayFlagRevisions(FactRevisionTarget target, List<IssueFact> changedFacts) {
    for (IssueFact fact : changedFacts) {
      publicationRepository.copyIssueDelayRevision(target, fact);
    }
  }

  /**
   * 用当前来源结果替换指定议题目标：写入仍存在根的候选修订，来源已消失的根登记删除证明。
   *
   * <p>不再删除已发布修订；删除通过发布时关闭旧修订区间体现，因此删除只对新的发布序号生效。
   *
   * @param target 候选修订写入目标
   * @param rootIds GitLab Issue 数据库根 ID 的非空集合
   * @param facts 目标范围当前仍存在的事实；空集合表示来源实体已删除
   */
  @Transactional
  public void replaceRootFacts(
      FactRevisionTarget target, List<Long> rootIds, List<IssueFact> facts) {
    List<Long> safeRootIds = sanitizeRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      throw new IllegalArgumentException("议题事实目标替换必须指定非空目标范围");
    }
    List<IssueFact> safeFacts = facts == null ? List.of() : facts;
    publicationRepository.requireWritableTarget(target);
    upsertIssueFacts(target, safeFacts);
    // 删除判定交给仓储按完整业务身份比较：本候选未写出对应身份的考虑过的根，才构成删除或跨项目移出。
    publicationRepository.recordDeletionsForRoots(
        target.table(),
        target.publicationId(),
        target.sourceSystem(),
        target.sourceInstance(),
        safeRootIds,
        target.coveredChangeVersion());
  }

  private List<Long> sanitizeRootIds(List<Long> rootIds) {
    if (rootIds == null || rootIds.isEmpty()) {
      return List.of();
    }
    return rootIds.stream()
        .filter(rootId -> rootId != null && rootId > 0L)
        .distinct()
        .sorted()
        .toList();
  }
}
