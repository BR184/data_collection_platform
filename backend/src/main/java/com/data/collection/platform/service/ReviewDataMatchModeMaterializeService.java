package com.data.collection.platform.service;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataMatchModeMaterializeService {
  private final ReviewDataMatchModeRecordRepository matchModeRecordRepository;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;

  public ReviewDataMatchModeMaterializeService(
      ReviewDataMatchModeRecordRepository matchModeRecordRepository,
      ReviewDataRecordPersistenceSupport persistenceSupport) {
    this.matchModeRecordRepository = matchModeRecordRepository;
    this.persistenceSupport = persistenceSupport;
  }

  //兼容模式-MatchMode
  public Long materializeRecord(Long matchModeRecordId) {
    Long existingRecordId = matchModeRecordRepository.findMaterializedRecordId(matchModeRecordId);
    if (existingRecordId != null) {
      return existingRecordId;
    }
    ReviewDataMatchModeRecordRepository.MatchModeRecordSource source =
        matchModeRecordRepository.getRecordSourceOrThrow(matchModeRecordId);
    ReviewDataMatchModeRecordRepository.ReportRow report = source.record();
    Long recordId =
        persistenceSupport.insertRecord(
            valueOrDefault(report.projectName(), "未标注项目名"),
            valueOrDefault(report.title(), "老平台评审记录"),
            valueOrDefault(ReviewDataModuleNameSupport.normalize(report.moduleName()), "未标注模块名"),
            valueOrDefault(firstText(report.reviewTypeStr(), report.docType(), report.sourceType()), "其他"),
            report.reviewTime() == null ? LocalDate.now() : report.reviewTime().toLocalDate(),
            valueOrDefault(report.reviewCharger(), "未填写"),
            report.defectValue() == null ? 0 : Math.max(0, report.defectValue()),
            valueOrDefault(report.title(), "老平台评审记录"),
            "未填写",
            "V1",
            report.notReachStandCause(),
            "match-mode-mongo",
            report.weightedDefectDensity() == null ? null : report.weightedDefectDensity().doubleValue());
    if (recordId == null) {
      throw new IllegalStateException("兼容模式评审记录转正式记录失败");
    }
    persistenceSupport.replaceExperts(recordId, report.reviewExperts());
    persistenceSupport.ensurePrimaryDescription(
        recordId,
        valueOrDefault(report.title(), "老平台评审记录"),
        "V1",
        "未填写",
        report.defectValue() == null ? 0 : Math.max(0, report.defectValue()));
    for (ReviewDataMatchModeRecordRepository.ProblemRow problem : source.problems()) {
      Long problemItemId =
          persistenceSupport.insertProblemItem(
              recordId,
              valueOrDefault(problem.reviewer(), "未填写"),
              problem.workload(),
              valueOrDefault(problem.reviewType(), "独立评审"),
              TextQuerySupport.normalizeDisplay(problem.position()),
              valueOrDefault(problem.problemType(), "无问题"),
              valueOrDefault(problem.description(), "待评审"),
              TextQuerySupport.normalizeDisplay(problem.suggestion()),
              TextQuerySupport.normalizeDisplay(problem.liablePerson()),
              TextQuerySupport.normalizeDisplay(problem.reasonForNotAccepting()),
              valueOrDefault(problem.problemStatus(), "未评审"));
      if (problemItemId != null) {
        matchModeRecordRepository.linkMaterializedProblem(
            -Math.abs(problem.id()),
            problem.legacyId(),
            recordId,
            problemItemId);
      }
    }
    persistenceSupport.refreshSearchIndex(recordId);
    matchModeRecordRepository.linkMaterializedRecord(matchModeRecordId, report.legacyId(), recordId);
    return recordId;
  }

  //兼容模式-MatchMode
  public Long materializedProblemItemIdOrThrow(Long matchModeProblemItemId) {
    Long problemItemId = matchModeRecordRepository.findMaterializedProblemItemId(matchModeProblemItemId);
    if (problemItemId == null) {
      throw new IllegalArgumentException("兼容模式评审问题尚未转为正式记录: " + matchModeProblemItemId);
    }
    return problemItemId;
  }

  private String firstText(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      String normalized = TextQuerySupport.normalizeDisplay(value);
      if (!normalized.isBlank()) {
        return normalized;
      }
    }
    return "";
  }

  private String valueOrDefault(String value, String fallback) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? fallback : normalized;
  }
}
