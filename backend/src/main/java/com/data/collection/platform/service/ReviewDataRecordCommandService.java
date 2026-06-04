package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewDataRecordCommandService {
  private static final String DEFAULT_PENDING_REVIEW_STATUS = "未评审";
  private static final String DEFAULT_PENDING_REVIEW_CATEGORY = "独立评审";
  private static final String DEFAULT_PENDING_PROBLEM_CATEGORY = "无问题";
  private static final String DEFAULT_PENDING_PROBLEM_DESCRIPTION = "待评审";

  private final ReviewDataRecordPersistenceSupport persistenceSupport;

  public ReviewDataRecordCommandService(ReviewDataRecordPersistenceSupport persistenceSupport) {
    this.persistenceSupport = persistenceSupport;
  }

  @Transactional
  public Long createRecord(ReviewDataRecordSaveRequest request) {
    Long recordId =
        persistenceSupport.insertRecord(
            request.projectName(),
            request.title(),
            request.moduleName(),
            request.reviewType(),
            request.reviewDate(),
            request.reviewOwner(),
            request.reviewScalePages(),
            request.reviewProduct(),
            request.authorName(),
            request.reviewVersion(),
            request.notReachStandardReason());
    if (recordId == null) {
      throw new IllegalStateException("创建评审记录失败");
    }

    persistenceSupport.replaceExperts(recordId, request.reviewExperts());
    if (Boolean.TRUE.equals(request.createPendingProblemItems())) {
      createPendingProblemItems(recordId, request.reviewExperts());
    }
    persistenceSupport.refreshSearchIndex(recordId);
    return recordId;
  }

  @Transactional
  public Long updateRecord(Long recordId, ReviewDataRecordSaveRequest request) {
    persistenceSupport.assertRecordExists(recordId);
    persistenceSupport.updateRecord(
        recordId,
        request.projectName(),
        request.title(),
        request.moduleName(),
        request.reviewType(),
        request.reviewDate(),
        request.reviewOwner(),
        request.reviewScalePages(),
        request.reviewProduct(),
        request.authorName(),
        request.reviewVersion(),
        request.notReachStandardReason());
    persistenceSupport.replaceExperts(recordId, request.reviewExperts());
    persistenceSupport.refreshSearchIndex(recordId);
    return recordId;
  }

  @Transactional
  public void deleteRecord(Long recordId) {
    persistenceSupport.assertRecordExists(recordId);
    persistenceSupport.softDeleteRecord(recordId);
  }

  @Transactional
  public Long createProblemItem(Long recordId, ReviewDataProblemItemSaveRequest request) {
    persistenceSupport.assertRecordExists(recordId);
    Long itemId =
        persistenceSupport.insertProblemItem(
            recordId,
            request.reviewerName(),
            request.workloadHours(),
            request.reviewCategory(),
            request.documentPosition(),
            request.problemCategory(),
            request.problemDescription(),
            request.suggestedSolution(),
            request.ownerName(),
            request.rejectionReason(),
            request.problemStatus());
    if (itemId == null) {
      throw new IllegalStateException("创建评审问题失败");
    }
    persistenceSupport.touchRecord(recordId);
    return itemId;
  }

  @Transactional
  public Long updateProblemItem(
      Long recordId, Long itemId, ReviewDataProblemItemSaveRequest request) {
    persistenceSupport.assertRecordExists(recordId);
    persistenceSupport.assertProblemItemExists(recordId, itemId);
    persistenceSupport.updateProblemItem(
        recordId,
        itemId,
        request.reviewerName(),
        request.workloadHours(),
        request.reviewCategory(),
        request.documentPosition(),
        request.problemCategory(),
        request.problemDescription(),
        request.suggestedSolution(),
        request.ownerName(),
        request.rejectionReason(),
        request.problemStatus());
    persistenceSupport.touchRecord(recordId);
    return itemId;
  }

  @Transactional
  public void deleteProblemItem(Long recordId, Long itemId) {
    persistenceSupport.assertRecordExists(recordId);
    persistenceSupport.assertProblemItemExists(recordId, itemId);
    persistenceSupport.softDeleteProblemItem(recordId, itemId);
    persistenceSupport.touchRecord(recordId);
  }

  private void createPendingProblemItems(Long recordId, java.util.List<String> experts) {
    if (experts == null || experts.isEmpty()) {
      return;
    }
    for (String expert : experts) {
      if (TextQuerySupport.trimToNull(expert) == null) {
        continue;
      }
      persistenceSupport.insertProblemItem(
          recordId,
          expert,
          0D,
          DEFAULT_PENDING_REVIEW_CATEGORY,
          "",
          DEFAULT_PENDING_PROBLEM_CATEGORY,
          DEFAULT_PENDING_PROBLEM_DESCRIPTION,
          "",
          "",
          "",
          DEFAULT_PENDING_REVIEW_STATUS);
    }
    persistenceSupport.touchRecord(recordId);
  }
}
