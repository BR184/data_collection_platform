package com.data.collection.platform.service;

import com.data.collection.platform.entity.CodeReviewIllegalRecordRowResponse;
import com.data.collection.platform.entity.CodeReviewRuleConfig;
import com.data.collection.platform.entity.CodeReviewRulePreviewSample;
import java.util.List;
import org.springframework.stereotype.Component;

/** 代码走查非法记录的来源行 → 视图 → 响应映射；42 字段顺序即 API 契约，不得调整。 */
@Component
class CodeReviewIllegalRecordResponseMapper {
  private final GitlabResourceLinkService issueLinkService;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;

  CodeReviewIllegalRecordResponseMapper(
      GitlabResourceLinkService issueLinkService,
      CodeReviewMatchModeSwitchService matchModeSwitchService) {
    this.issueLinkService = issueLinkService;
    this.matchModeSwitchService = matchModeSwitchService;
  }

  boolean compatibilityReadEnabled() {
    return matchModeSwitchService.isCodeReviewCompatibilityReadEnabled();
  }

  CodeReviewIllegalRecordView toView(CodeReviewIllegalRecordSource source) {
    List<String> illegalTypes =
        compatibilityReadEnabled()
            ? CodeReviewIllegalRuleRegistry.evaluateLegacyMatchModeIllegalTypes(source)
            : CodeReviewIllegalRuleRegistry.evaluateIllegalTypes(source);
    String mergeRequestLink = mergeRequestLink(source);
    return new CodeReviewIllegalRecordView(
        "merge_request",
        source.sourceInstance(),
        source.mergeRequestId(),
        source.mergeRequestIid(),
        source.projectId(),
        TextQuerySupport.normalizeDisplay(source.mergeRequestContent()),
        mergeRequestLink,
        TextQuerySupport.normalizeDisplay(source.owner()),
        TextQuerySupport.normalizeDisplay(source.projectName()),
        TextQuerySupport.normalizeDisplay(source.repositoryName()),
        source.mergedAt(),
        TextQuerySupport.normalizeDisplay(source.author()),
        TextQuerySupport.normalizeDisplay(source.mergedBy()),
        TextQuerySupport.normalizeDisplay(source.moduleName()),
        TextQuerySupport.normalizeDisplay(source.targetBranch()),
        illegalTypes,
        TextQuerySupport.normalizeDisplay(source.reviewerNames()),
        TextQuerySupport.normalizeDisplay(source.assigneeNames()),
        TextQuerySupport.normalizeDisplay(source.reviewStatus()),
        source.reviewDurationMinutes(),
        TextQuerySupport.normalizeDisplay(source.reviewExceptionReason()),
        source.codeWalkthroughDate(),
        TextQuerySupport.normalizeDisplay(source.scanStatus()),
        source.scanBugCount(),
        TextQuerySupport.normalizeDisplay(source.annotationRateResult()),
        TextQuerySupport.normalizeDisplay(source.bugCountResult()),
        source.commentRate(),
        source.defectCount(),
        source.addedLines(),
        source.deletedLines(),
        source.codeSpecificationCount(),
        source.codeLogicSpecificationCount(),
        source.performanceSpecificationCount(),
        source.designSpecificationCount(),
        source.otherSpecificationCount(),
        source.reviewSpeedLocPerHour(),
        source.reviewSpeedKlocPerHour(),
        source.reviewDefectDensityPerKloc(),
        source.reviewEfficiencyPerHour(),
        source.commitCount(),
        displayCommitRate(source),
        TextQuerySupport.normalizeDisplay(source.functionName()),
        source.clangAddedLineCount());
  }

  CodeReviewIllegalRecordRowResponse toResponse(CodeReviewIllegalRecordView row) {
    return toResponse(row, null);
  }

  CodeReviewIllegalRecordRowResponse toResponse(
      CodeReviewIllegalRecordView row, CodeReviewRuleConfig ruleConfig) {
    String link = TextQuerySupport.trimToNull(row.mergeRequestLink());
    List<String> illegalTypes =
        ruleConfig == null
            ? row.illegalTypes()
            : CodeReviewRuleConfigSupport.explainRow(row, ruleConfig).stream()
                .map(reason -> reason.replaceFirst("^满足：", ""))
                .toList();
    return new CodeReviewIllegalRecordRowResponse(
        row.requestType(),
        row.sourceInstance(),
        row.mergeRequestId(),
        row.mergeRequestIid(),
        row.projectId(),
        row.mergeRequestContent(),
        link,
        row.owner(),
        row.projectName(),
        row.repositoryName(),
        row.mergedAt(),
        row.author(),
        row.mergedBy(),
        row.moduleName(),
        row.targetBranch(),
        illegalTypes,
        row.reviewerNames(),
        row.assigneeNames(),
        row.reviewStatus(),
        row.reviewDurationMinutes(),
        row.reviewExceptionReason(),
        row.codeWalkthroughDate(),
        row.scanStatus(),
        row.scanBugCount(),
        row.annotationRateResult(),
        row.bugCountResult(),
        row.commentRate(),
        row.defectCount(),
        row.addedLines(),
        row.deletedLines(),
        row.codeSpecificationCount(),
        row.codeLogicSpecificationCount(),
        row.performanceSpecificationCount(),
        row.designSpecificationCount(),
        row.otherSpecificationCount(),
        row.reviewSpeedLocPerHour(),
        row.reviewSpeedKlocPerHour(),
        row.defectDensityPerKloc(),
        row.reviewEfficiencyPerHour(),
        row.commitCount(),
        row.commitRate(),
        row.functionName(),
        row.clangAddedLineCount());
  }

  CodeReviewRulePreviewSample toRulePreviewSample(
      CodeReviewIllegalRecordView row, CodeReviewRuleConfig ruleConfig) {
    return new CodeReviewRulePreviewSample(
        row.mergeRequestId(),
        row.mergeRequestIid(),
        row.projectName(),
        row.moduleName(),
        row.author(),
        row.targetBranch(),
        row.mergeRequestContent(),
        CodeReviewRuleConfigSupport.explainRow(row, ruleConfig));
  }

  private String mergeRequestLink(CodeReviewIllegalRecordSource source) {
    String link =
        issueLinkService.mergeRequestUrl(source.sourceInstance(), source.projectId(), source.mergeRequestIid());
    if (link != null || !compatibilityReadEnabled()) {
      return link;
    }
    //兼容模式-MatchMode：老平台代码走查表来自 MySQL 兼容表，project_id 可能为 0，
    //无法走 GitLab ODS 项目路径，需要按老平台代码走查页面的 MR 链接规则生成。
    return issueLinkService.legacyCodeReviewMergeRequestUrl(
        source.sourceInstance(), source.repositoryName(), source.mergeRequestIid());
  }

  private Integer displayCommitRate(CodeReviewIllegalRecordSource source) {
    if (!compatibilityReadEnabled()) {
      return source.commitRate();
    }
    return legacyCommitRate(source.addedLines(), source.commitCount());
  }

  private Integer legacyCommitRate(Integer addedLines, Integer commitCount) {
    if (addedLines == null || commitCount == null || addedLines <= 0 || commitCount <= 0) {
      return 0;
    }
    return addedLines / commitCount;
  }
}
