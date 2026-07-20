package com.data.collection.platform.service;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
//兼容模式-MatchMode：把老平台 Mongo 评审数据物化为新平台正式表。
// 后续老平台交接完成并删除兼容模式时，本 Service 可整体删除；正式新增/编辑评审不依赖本类。
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
  @Transactional
  public Long materializeRecord(Long matchModeRecordId) {
    return materializeRecordWithResult(matchModeRecordId).recordId();
  }

  //兼容模式-MatchMode：转正式时优先把老平台 docType 映射到正式表 review_type，保证关闭兼容模式后导出口径仍可用。
  @Transactional
  public MaterializeResult materializeRecordWithResult(Long matchModeRecordId) {
    Long existingRecordId = matchModeRecordRepository.findMaterializedRecordId(matchModeRecordId);
    ReviewDataMatchModeRecordRepository.MatchModeRecordSource source =
        matchModeRecordRepository.getRecordSourceOrThrow(matchModeRecordId);
    ReviewDataMatchModeRecordRepository.ReportRow report = source.record();
    ReviewDataMatchModeRecordRepository.DescriptionRow primaryDescription = source.primaryDescription();
    Integer reviewScalePages = source.reviewScalePages();
    String reviewProduct =
        valueOrDefault(primaryDescription == null ? null : primaryDescription.reviewProduct(), report.title());
    String reviewVersion =
        valueOrDefault(primaryDescription == null ? null : primaryDescription.version(), "V1");
    String authorName =
        valueOrDefault(primaryDescription == null ? null : primaryDescription.author(), "未填写");
    boolean inserted = existingRecordId == null;
    Long recordId = existingRecordId;
    if (recordId == null) {
      recordId =
          persistenceSupport.insertRecord(
              valueOrDefault(report.projectName(), "未标注项目名"),
              valueOrDefault(report.title(), "老平台评审记录"),
              valueOrDefault(ReviewDataModuleNameSupport.normalize(report.moduleName()), "未标注模块名"),
              valueOrDefault(firstText(report.docType(), report.sourceType(), report.reviewTypeStr()), "其他"),
              report.reviewTime() == null ? LocalDate.now() : report.reviewTime().toLocalDate(),
              valueOrDefault(report.reviewCharger(), "未填写"),
              reviewScalePages,
              reviewProduct,
              authorName,
              reviewVersion,
              report.notReachStandCause(),
              "match-mode-mongo",
              materializedWeightedDefectDensity());
      if (recordId == null) {
        throw new IllegalStateException("兼容模式评审记录转正式记录失败");
      }
    } else {
      persistenceSupport.updateRecord(
          recordId,
          valueOrDefault(report.projectName(), "未标注项目名"),
          valueOrDefault(report.title(), "老平台评审记录"),
          valueOrDefault(ReviewDataModuleNameSupport.normalize(report.moduleName()), "未标注模块名"),
          valueOrDefault(firstText(report.docType(), report.sourceType(), report.reviewTypeStr()), "其他"),
          report.reviewTime() == null ? LocalDate.now() : report.reviewTime().toLocalDate(),
          valueOrDefault(report.reviewCharger(), "未填写"),
          reviewScalePages,
          reviewProduct,
          authorName,
          reviewVersion,
          report.notReachStandCause(),
          "match-mode-mongo",
          materializedWeightedDefectDensity());
      persistenceSupport.softDeleteProblemItems(recordId);
    }
    persistenceSupport.replaceExperts(recordId, report.reviewExperts());
    persistenceSupport.ensurePrimaryDescription(
        recordId,
        reviewProduct,
        reviewVersion,
        authorName,
        reviewScalePages);
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
    return new MaterializeResult(recordId, inserted);
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

  private Double materializedWeightedDefectDensity() {
    //兼容模式-MatchMode：正式表列表和导出统一从 review_problem_items 重算加权密度；
    //转正式时不搬运老 Mongo 中可能陈旧或为 0 的派生 weightedDefectDensity，避免兼容数据污染正式口径。
    return null;
  }

  //兼容模式-MatchMode
  public record MaterializeResult(Long recordId, boolean inserted) {}
}
