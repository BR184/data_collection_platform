package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewDataRecordCommandService {
  private static final String DEFAULT_PENDING_REVIEW_STATUS = "未评审";
  private static final String DEFAULT_PENDING_REVIEW_CATEGORY = "独立评审";
  private static final String DEFAULT_PENDING_PROBLEM_CATEGORY = "无问题";
  private static final String DEFAULT_PENDING_PROBLEM_DESCRIPTION = "待评审";

  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;
  private final ReviewDataMatchModeMaterializeService matchModeMaterializeService;

  public ReviewDataRecordCommandService(
      ReviewDataRecordPersistenceSupport persistenceSupport,
      CodeReviewMatchModeSwitchService matchModeSwitchService,
      ReviewDataMatchModeMaterializeService matchModeMaterializeService) {
    this.persistenceSupport = persistenceSupport;
    this.matchModeSwitchService = matchModeSwitchService;
    this.matchModeMaterializeService = matchModeMaterializeService;
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
            request.notReachStandardReason(),
            request.sourceFileName(),
            request.weightedDefectDensity());
    if (recordId == null) {
      throw new IllegalStateException("创建评审记录失败");
    }

    persistenceSupport.replaceExperts(recordId, request.reviewExperts());
    persistLegacyParityDetails(recordId, request);
    if (Boolean.TRUE.equals(request.createPendingProblemItems())) {
      createPendingProblemItems(recordId, request.reviewExperts());
    }
    persistenceSupport.refreshSearchIndex(recordId);
    return recordId;
  }

  @Transactional
  public Long updateRecord(Long recordId, ReviewDataRecordSaveRequest request) {
    recordId = materializeRecordIfNeeded(recordId);
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
        request.notReachStandardReason(),
        request.sourceFileName(),
        request.weightedDefectDensity());
    persistenceSupport.replaceExperts(recordId, request.reviewExperts());
    persistLegacyParityDetails(recordId, request);
    syncPendingProblemItemsWithExperts(recordId, request.reviewExperts());
    persistenceSupport.refreshSearchIndex(recordId);
    return recordId;
  }

  @Transactional
  public void deleteRecord(Long recordId) {
    recordId = materializeRecordIfNeeded(recordId);
    persistenceSupport.assertRecordExists(recordId);
    persistenceSupport.softDeleteRecord(recordId);
  }

  @Transactional
  public Long createProblemItem(Long recordId, ReviewDataProblemItemSaveRequest request) {
    recordId = materializeRecordIfNeeded(recordId);
    persistenceSupport.assertRecordExists(recordId);
    String problemStatus = defaultPendingStatus(request.problemStatus());
    ReviewDataProblemItemResponse pendingItem = findPendingProblemItem(recordId, request.reviewerName());
    if (pendingItem != null) {
      persistenceSupport.updateProblemItem(
          recordId,
          pendingItem.id(),
          request.reviewerName(),
          request.workloadHours(),
          request.reviewCategory(),
          request.documentPosition(),
          request.problemCategory(),
          request.problemDescription(),
          request.suggestedSolution(),
          request.ownerName(),
          request.rejectionReason(),
          problemStatus);
      persistenceSupport.touchRecord(recordId);
      return pendingItem.id();
    }
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
            problemStatus);
    if (itemId == null) {
      throw new IllegalStateException("创建评审问题失败");
    }
    persistenceSupport.touchRecord(recordId);
    return itemId;
  }

  @Transactional
  public Long updateProblemItem(
      Long recordId, Long itemId, ReviewDataProblemItemSaveRequest request) {
    boolean matchModeProblemItem = isMatchModeId(itemId);
    recordId = materializeRecordIfNeeded(recordId);
    itemId = matchModeProblemItem ? matchModeMaterializeService.materializedProblemItemIdOrThrow(itemId) : itemId;
    persistenceSupport.assertRecordExists(recordId);
    persistenceSupport.assertProblemItemExists(recordId, itemId);
    String problemStatus = requireProblemStatus(request.problemStatus());
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
        problemStatus);
    persistenceSupport.touchRecord(recordId);
    return itemId;
  }

  @Transactional
  public void deleteProblemItem(Long recordId, Long itemId) {
    boolean matchModeProblemItem = isMatchModeId(itemId);
    recordId = materializeRecordIfNeeded(recordId);
    itemId = matchModeProblemItem ? matchModeMaterializeService.materializedProblemItemIdOrThrow(itemId) : itemId;
    persistenceSupport.assertRecordExists(recordId);
    ReviewDataProblemItemResponse deletedItem = persistenceSupport.getProblemItemOrThrow(recordId, itemId);
    persistenceSupport.softDeleteProblemItem(recordId, itemId);
    if (removeExpertWithoutProblemItems(recordId, deletedItem.reviewerName())) {
      persistenceSupport.refreshSearchIndex(recordId);
    }
    persistenceSupport.touchRecord(recordId);
  }

  private void createPendingProblemItems(Long recordId, List<String> experts) {
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

  private void syncPendingProblemItemsWithExperts(Long recordId, List<String> experts) {
    List<ReviewDataProblemItemResponse> existingItems = persistenceSupport.listProblemItems(recordId);
    Set<String> requestedExperts = normalizeExpertNames(experts);

    boolean deleted = deletePendingProblemItemsForRemovedExperts(recordId, existingItems, requestedExperts);
    boolean created = createMissingPendingProblemItems(recordId, experts, existingItems);
    if (deleted || created) {
      persistenceSupport.touchRecord(recordId);
    }
  }

  private boolean createMissingPendingProblemItems(
      Long recordId, List<String> experts, List<ReviewDataProblemItemResponse> existingItems) {
    if (experts == null || experts.isEmpty()) {
      return false;
    }

    Set<String> reviewersWithItems = new HashSet<>();
    for (ReviewDataProblemItemResponse item : existingItems) {
      String reviewer = TextQuerySupport.normalizeForMatch(item.reviewerName());
      if (reviewer != null) {
        reviewersWithItems.add(reviewer);
      }
    }

    boolean created = false;
    for (String expert : experts) {
      String normalizedExpert = TextQuerySupport.normalizeForMatch(expert);
      if (normalizedExpert == null) {
        continue;
      }
      if (reviewersWithItems.contains(normalizedExpert)) {
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
      reviewersWithItems.add(normalizedExpert);
      created = true;
    }

    return created;
  }

  private boolean deletePendingProblemItemsForRemovedExperts(
      Long recordId, List<ReviewDataProblemItemResponse> existingItems, Set<String> requestedExperts) {
    boolean deleted = false;
    for (ReviewDataProblemItemResponse item : existingItems) {
      String reviewer = TextQuerySupport.normalizeForMatch(item.reviewerName());
      if (reviewer == null || requestedExperts.contains(reviewer)) {
        continue;
      }
      if (!isDefaultPendingProblemItem(item)) {
        continue;
      }
      persistenceSupport.softDeleteProblemItem(recordId, item.id());
      deleted = true;
    }
    return deleted;
  }

  private boolean removeExpertWithoutProblemItems(Long recordId, String reviewerName) {
    String normalizedReviewer = TextQuerySupport.normalizeForMatch(reviewerName);
    if (normalizedReviewer == null) {
      return false;
    }

    boolean hasRemainingProblemItem =
        persistenceSupport.listProblemItems(recordId).stream()
            .map(ReviewDataProblemItemResponse::reviewerName)
            .map(TextQuerySupport::normalizeForMatch)
            .anyMatch(reviewer -> Objects.equals(reviewer, normalizedReviewer));
    if (hasRemainingProblemItem) {
      return false;
    }

    List<String> experts = persistenceSupport.listRecordExperts(recordId);
    List<String> retainedExperts = new ArrayList<>();
    boolean removed = false;
    for (String expert : experts) {
      if (Objects.equals(TextQuerySupport.normalizeForMatch(expert), normalizedReviewer)) {
        removed = true;
        continue;
      }
      retainedExperts.add(expert);
    }
    if (removed) {
      persistenceSupport.replaceExperts(recordId, retainedExperts);
    }
    return removed;
  }

  private Set<String> normalizeExpertNames(List<String> experts) {
    Set<String> result = new HashSet<>();
    if (experts == null) {
      return result;
    }
    for (String expert : experts) {
      String normalizedExpert = TextQuerySupport.normalizeForMatch(expert);
      if (normalizedExpert != null) {
        result.add(normalizedExpert);
      }
    }
    return result;
  }

  private boolean isDefaultPendingProblemItem(ReviewDataProblemItemResponse item) {
    return isZeroWorkload(item.workloadHours())
        && DEFAULT_PENDING_REVIEW_CATEGORY.equals(item.reviewCategory())
        && isBlank(item.documentPosition())
        && DEFAULT_PENDING_PROBLEM_CATEGORY.equals(item.problemCategory())
        && DEFAULT_PENDING_PROBLEM_DESCRIPTION.equals(item.problemDescription())
        && isBlank(item.suggestedSolution())
        && isBlank(item.ownerName())
        && isBlank(item.rejectionReason())
        && DEFAULT_PENDING_REVIEW_STATUS.equals(item.problemStatus());
  }

  private boolean isZeroWorkload(Double workloadHours) {
    return workloadHours == null || Double.compare(workloadHours, 0D) == 0;
  }

  private boolean isBlank(String value) {
    return TextQuerySupport.trimToNull(value) == null;
  }

  private String defaultPendingStatus(String problemStatus) {
    String normalized = TextQuerySupport.trimToNull(problemStatus);
    return normalized == null ? DEFAULT_PENDING_REVIEW_STATUS : requireUserSelectableProblemStatus(normalized);
  }

  private String requireProblemStatus(String problemStatus) {
    String normalized = TextQuerySupport.trimToNull(problemStatus);
    if (normalized == null) {
      throw new IllegalArgumentException("编辑评审问题时必须选择问题状态");
    }
    return requireUserSelectableProblemStatus(normalized);
  }

  private String requireUserSelectableProblemStatus(String problemStatus) {
    if (DEFAULT_PENDING_REVIEW_STATUS.equals(problemStatus)) {
      throw new IllegalArgumentException("未评审为系统默认状态，不能手动选择");
    }
    return problemStatus;
  }

  private void persistLegacyParityDetails(Long recordId, ReviewDataRecordSaveRequest request) {
    if (request.descriptions() == null || request.descriptions().isEmpty()) {
      persistenceSupport.ensurePrimaryDescription(
          recordId,
          request.reviewProduct(),
          request.reviewVersion(),
          request.authorName(),
          request.reviewScalePages());
    } else {
      persistenceSupport.replaceDescriptions(recordId, request.descriptions());
    }
    if (request.contents() != null) {
      persistenceSupport.replaceContents(recordId, request.contents());
    }
  }

  private ReviewDataProblemItemResponse findPendingProblemItem(Long recordId, String reviewerName) {
    String normalizedReviewer = TextQuerySupport.normalizeForMatch(reviewerName);
    if (normalizedReviewer == null) {
      return null;
    }
    return persistenceSupport.listProblemItems(recordId).stream()
        .filter(item -> Objects.equals(TextQuerySupport.normalizeForMatch(item.reviewerName()), normalizedReviewer))
        .filter(item -> DEFAULT_PENDING_REVIEW_STATUS.equals(item.problemStatus()))
        .findFirst()
        .orElse(null);
  }

  private Long materializeRecordIfNeeded(Long recordId) {
    //兼容模式-MatchMode：即使页面已切到正式读源，历史路由传入的兼容负 ID 仍可安全物化。
    if (!isMatchModeId(recordId)) {
      return recordId;
    }
    return matchModeMaterializeService.materializeRecord(recordId);
  }

  private boolean isMatchModeId(Long id) {
    return id != null && id < 0;
  }
}
