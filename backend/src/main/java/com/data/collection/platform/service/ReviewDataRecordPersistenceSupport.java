package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataContentResponse;
import com.data.collection.platform.entity.ReviewDataContentSaveRequest;
import com.data.collection.platform.entity.ReviewDataDescriptionResponse;
import com.data.collection.platform.entity.ReviewDataDescriptionSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataRecordPersistenceSupport {
  private final ReviewDataRecordReadRepository recordReadRepository;
  private final ReviewDataRecordWriteRepository recordWriteRepository;
  private final ReviewDataExpertRepository expertRepository;
  private final ReviewDataProblemItemRepository problemItemRepository;
  private final ReviewDataLegacyParityRepository legacyParityRepository;

  public ReviewDataRecordPersistenceSupport(
      ReviewDataRecordReadRepository recordReadRepository,
      ReviewDataRecordWriteRepository recordWriteRepository,
      ReviewDataExpertRepository expertRepository,
      ReviewDataProblemItemRepository problemItemRepository,
      ReviewDataLegacyParityRepository legacyParityRepository) {
    this.recordReadRepository = recordReadRepository;
    this.recordWriteRepository = recordWriteRepository;
    this.expertRepository = expertRepository;
    this.problemItemRepository = problemItemRepository;
    this.legacyParityRepository = legacyParityRepository;
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

  public List<ReviewDataRecordRowResponse> loadRecordsForFilterOptions() {
    return recordReadRepository.loadRecordsForFilterOptions();
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
      StatisticFilterGroup filterGroup,
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
        filterGroup,
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

  public List<ReviewDataDescriptionResponse> listDescriptions(Long recordId) {
    return legacyParityRepository.listDescriptions(recordId);
  }

  public List<ReviewDataContentResponse> listContents(Long recordId) {
    return legacyParityRepository.listContents(recordId);
  }

  public Map<Long, List<ReviewDataProblemItemResponse>> listProblemItemsByRecordIds(List<Long> recordIds) {
    return problemItemRepository.listProblemItemsByRecordIds(recordIds);
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

  public void ensurePrimaryDescription(
      Long recordId,
      String reviewProduct,
      String reviewVersion,
      String authorName,
      Integer reviewScalePages) {
    legacyParityRepository.ensurePrimaryDescription(
        recordId, reviewProduct, reviewVersion, authorName, reviewScalePages);
  }

  public void replaceDescriptions(Long recordId, List<ReviewDataDescriptionSaveRequest> descriptions) {
    legacyParityRepository.replaceDescriptions(recordId, descriptions);
  }

  public void replaceContents(Long recordId, List<ReviewDataContentSaveRequest> contents) {
    legacyParityRepository.replaceContents(recordId, contents);
  }

  public void refreshMissingSearchIndexes(int limit) {
    recordWriteRepository.refreshMissingSearchIndexes(limit);
  }

  public boolean hasMissingSearchIndexes() {
    return recordReadRepository.hasMissingSearchIndexes();
  }

  public boolean hasMissingTitleSearchIndexes() {
    return recordReadRepository.hasMissingTitleSearchIndexes();
  }

  public boolean existsDuplicateRecord(
      String projectName,
      String title,
      String reviewType,
      java.time.LocalDate reviewDate,
      String reviewVersion) {
    return recordReadRepository.existsDuplicateRecord(
        projectName, title, reviewType, reviewDate, reviewVersion);
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
      String reviewVersion,
      String notReachStandardReason,
      String sourceFileName,
      Double weightedDefectDensity) {
    return insertRecord(
        projectName, title, moduleName, reviewType, reviewDate, reviewOwner, reviewScalePages,
        reviewProduct, authorName, reviewVersion, notReachStandardReason, sourceFileName,
        weightedDefectDensity, null);
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
      String reviewVersion,
      String notReachStandardReason,
      String sourceFileName,
      Double weightedDefectDensity,
      String createdBy) {
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
        reviewVersion,
        notReachStandardReason,
        sourceFileName,
        weightedDefectDensity,
        createdBy);
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
      String reviewVersion,
      String notReachStandardReason,
      String sourceFileName,
      Double weightedDefectDensity) {
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
        reviewVersion,
        notReachStandardReason,
        sourceFileName,
        weightedDefectDensity);
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
    return insertProblemItem(
        recordId, reviewerName, workloadHours, reviewCategory, documentPosition, problemCategory,
        problemDescription, suggestedSolution, ownerName, rejectionReason, problemStatus, null);
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
      String problemStatus,
      String createdBy) {
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
        problemStatus,
        createdBy);
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

  public void softDeleteProblemItems(Long recordId) {
    problemItemRepository.softDeleteProblemItems(recordId);
  }
}
