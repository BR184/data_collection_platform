package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataRecordPersistenceSupport {
  private final ReviewDataRecordReadRepository recordReadRepository;
  private final ReviewDataRecordWriteRepository recordWriteRepository;
  private final ReviewDataExpertRepository expertRepository;
  private final ReviewDataProblemItemRepository problemItemRepository;

  public ReviewDataRecordPersistenceSupport(
      ReviewDataRecordReadRepository recordReadRepository,
      ReviewDataRecordWriteRepository recordWriteRepository,
      ReviewDataExpertRepository expertRepository,
      ReviewDataProblemItemRepository problemItemRepository) {
    this.recordReadRepository = recordReadRepository;
    this.recordWriteRepository = recordWriteRepository;
    this.expertRepository = expertRepository;
    this.problemItemRepository = problemItemRepository;
  }

  public List<ReviewDataRecordRowResponse> loadRecords(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String keyword) {
    return recordReadRepository.loadRecords(
        title, projectName, moduleName, reviewOwner, reviewType, problemStatus, reviewExpert, keyword);
  }

  public Map<Long, List<String>> loadProblemStatusesByRecordIds(
      List<ReviewDataRecordRowResponse> records) {
    return recordReadRepository.loadProblemStatusesByRecordIds(records);
  }

  public ReviewDataRecordReadRepository.RecordPageResult loadRecordPage(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String keyword,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    return recordReadRepository.loadRecordPage(
        title,
        projectName,
        moduleName,
        reviewOwner,
        reviewType,
        problemStatus,
        reviewExpert,
        keyword,
        page,
        size,
        sortField,
        sortOrder);
  }

  public ReviewDataRecordRowResponse getRecordOrThrow(Long recordId) {
    return recordReadRepository.getRecordOrThrow(recordId);
  }

  public List<String> listRecordExperts(Long recordId) {
    return expertRepository.listRecordExperts(recordId);
  }

  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    return problemItemRepository.listProblemItems(recordId);
  }

  public ReviewDataProblemItemResponse getProblemItemOrThrow(Long recordId, Long itemId) {
    return problemItemRepository.getProblemItemOrThrow(recordId, itemId);
  }

  public void assertRecordExists(Long recordId) {
    recordWriteRepository.assertRecordExists(recordId);
  }

  public void assertProblemItemExists(Long recordId, Long itemId) {
    problemItemRepository.assertProblemItemExists(recordId, itemId);
  }

  public void replaceExperts(Long recordId, List<String> experts) {
    expertRepository.replaceExperts(recordId, experts);
  }

  public void touchRecord(Long recordId) {
    recordWriteRepository.touchRecord(recordId);
  }

  public void refreshSearchIndex(Long recordId) {
    recordWriteRepository.refreshSearchIndex(recordId);
  }

  public void refreshMissingSearchIndexes(int limit) {
    recordWriteRepository.refreshMissingSearchIndexes(limit);
  }

  public boolean hasMissingSearchIndexes() {
    return recordReadRepository.hasMissingSearchIndexes();
  }

  public List<String> loadExpertOptions() {
    return expertRepository.loadExpertOptions();
  }

  public Long insertRecord(
      String projectName,
      String title,
      String moduleName,
      String reviewType,
      java.time.LocalDate reviewDate,
      String reviewOwner,
      Integer reviewScalePages,
      String reviewProduct,
      String authorName,
      String reviewVersion) {
    return recordWriteRepository.insertRecord(
        projectName,
        title,
        moduleName,
        reviewType,
        reviewDate,
        reviewOwner,
        reviewScalePages,
        reviewProduct,
        authorName,
        reviewVersion);
  }

  public void updateRecord(
      Long recordId,
      String projectName,
      String title,
      String moduleName,
      String reviewType,
      java.time.LocalDate reviewDate,
      String reviewOwner,
      Integer reviewScalePages,
      String reviewProduct,
      String authorName,
      String reviewVersion) {
    recordWriteRepository.updateRecord(
        recordId,
        projectName,
        title,
        moduleName,
        reviewType,
        reviewDate,
        reviewOwner,
        reviewScalePages,
        reviewProduct,
        authorName,
        reviewVersion);
  }

  public void softDeleteRecord(Long recordId) {
    recordWriteRepository.softDeleteRecord(recordId);
  }

  public Long insertProblemItem(
      Long recordId,
      String reviewerName,
      Double workloadHours,
      String reviewCategory,
      String documentPosition,
      String problemCategory,
      String problemDescription,
      String suggestedSolution,
      String ownerName,
      String rejectionReason,
      String problemStatus) {
    return problemItemRepository.insertProblemItem(
        recordId,
        reviewerName,
        workloadHours,
        reviewCategory,
        documentPosition,
        problemCategory,
        problemDescription,
        suggestedSolution,
        ownerName,
        rejectionReason,
        problemStatus);
  }

  public void updateProblemItem(
      Long recordId,
      Long itemId,
      String reviewerName,
      Double workloadHours,
      String reviewCategory,
      String documentPosition,
      String problemCategory,
      String problemDescription,
      String suggestedSolution,
      String ownerName,
      String rejectionReason,
      String problemStatus) {
    problemItemRepository.updateProblemItem(
        recordId,
        itemId,
        reviewerName,
        workloadHours,
        reviewCategory,
        documentPosition,
        problemCategory,
        problemDescription,
        suggestedSolution,
        ownerName,
        rejectionReason,
        problemStatus);
  }

  public void softDeleteProblemItem(Long recordId, Long itemId) {
    problemItemRepository.softDeleteProblemItem(recordId, itemId);
  }
}
