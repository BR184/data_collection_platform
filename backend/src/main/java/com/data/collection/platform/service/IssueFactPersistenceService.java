package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以同一事务提交议题事实及其客户成员关系。 */
@Service
public class IssueFactPersistenceService {
  private final IssueFactMapper issueFactMapper;
  private final IssueFactCustomerMembershipRepository customerMembershipRepository;

  public IssueFactPersistenceService(
      IssueFactMapper issueFactMapper,
      IssueFactCustomerMembershipRepository customerMembershipRepository) {
    this.issueFactMapper = issueFactMapper;
    this.customerMembershipRepository = customerMembershipRepository;
  }

  /**
   * 批量写入事实和客户成员。成员写入与事实更新必须同时成功，避免展示字段与客户筛选范围不一致。
   *
   * @param facts 同一事实构建批次的议题事实
   */
  @Transactional
  public void upsertIssueFacts(List<IssueFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    issueFactMapper.batchUpsert(facts);
    customerMembershipRepository.replaceForFacts(facts);
  }
}
