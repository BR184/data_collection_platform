package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 代码走查非法记录页的筛选候选值装配：请求类型/非法类型固定项、legacy 下拉清洗、
 * DGM 项目候选合并与 CC 兜底。兼容模式差异通过 matchMode 参数显式传入。
 */
@Component
class CodeReviewIllegalRecordFilterOptionAssembler {
  private static final String LEGACY_DEFAULT_SOURCE = "cc";
  private static final List<String> LEGACY_EXTRA_PROJECT_NAME_OPTIONS =
      List.of("广数CAM", "CC2025R4", "CC2026R1", "CC 2025 R4&2026 R1");

  private static final List<OptionItemResponse> REQUEST_TYPE_OPTIONS =
      List.of(new OptionItemResponse("合并请求", "merge_request"));

  private static final List<OptionItemResponse> LEGACY_ILLEGAL_TYPE_OPTIONS =
      List.of(
          new OptionItemResponse("未标注项目名称", CodeReviewIllegalRuleRegistry.LEGACY_MISSING_PROJECT_FILTER_LABEL),
          new OptionItemResponse("未标注模块名称", CodeReviewIllegalRuleRegistry.LEGACY_MISSING_MODULE_FILTER_LABEL),
          new OptionItemResponse("无代码走查", CodeReviewIllegalRuleRegistry.LEGACY_MISSING_REVIEW_FILTER_LABEL),
          new OptionItemResponse("未代码扫描", CodeReviewIllegalRuleRegistry.LEGACY_NOT_SCANNED_FILTER_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL, CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.COMMENT_RATE_NOT_PASS_LABEL, CodeReviewIllegalRuleRegistry.COMMENT_RATE_NOT_PASS_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.SCAN_FAILED_LABEL, CodeReviewIllegalRuleRegistry.SCAN_FAILED_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.CLANG_RESULT_FALSE_LABEL, CodeReviewIllegalRuleRegistry.CLANG_RESULT_FALSE_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.GITLAB_ERROR_LABEL, CodeReviewIllegalRuleRegistry.GITLAB_ERROR_LABEL));

  List<OptionItemResponse> requestTypeOptions() {
    return REQUEST_TYPE_OPTIONS;
  }

  List<OptionItemResponse> legacyIllegalTypeOptions() {
    return LEGACY_ILLEGAL_TYPE_OPTIONS;
  }

  List<OptionItemResponse> toProjectOptions(
      List<CodeReviewIllegalRecordFilterProjectOption> projects) {
    return projects.stream()
        .filter(project -> project.projectId() != null)
        .collect(
            java.util.stream.Collectors.toMap(
                CodeReviewIllegalRecordFilterProjectOption::projectId,
                project -> new OptionItemResponse(
                    projectOptionLabel(project), String.valueOf(project.projectId())),
                (left, right) -> left,
                java.util.LinkedHashMap::new))
        .values()
        .stream()
        .toList();
  }

  List<OptionItemResponse> toLegacyOptions(List<String> values) {
    return OptionItemResponseFactory.fromLegacyBusinessValues(values);
  }

  <T> List<OptionItemResponse> toLegacyOptions(List<T> rows, Function<T, String> extractor) {
    return OptionItemResponseFactory.fromLegacyBusinessValues(rows.stream().map(extractor).toList());
  }

  List<OptionItemResponse> toCodeReviewProjectNameOptions(
      boolean matchMode,
      String source,
      List<String> values,
      CodeReviewDgmGitlabProjectOptionService dgmProjectOptionService) {
    List<String> rawValues = new ArrayList<>(values == null ? List.of() : values);
    if ("dgm".equalsIgnoreCase(TextQuerySupport.trimToNull(source))) {
      //兼容模式-MatchMode：DGM 项目名称候选不依赖当前兼容表/正式事实表的数据量，
      //而是额外合并系统设置中维护的 GitLab API 本地缓存，确保交接期和非兼容模式候选一致。
      rawValues.addAll(dgmProjectOptionService.listProjectNames());
    }
    if (matchMode && isMatchModeCcSource(source)) {
      //兼容模式-MatchMode：老平台只在 CC/CrownCAD 项目下拉补充这四项；
      //DGM 与非兼容事实表不得继承此兜底，便于后续删除 Match mode 时整体移除。
      rawValues.addAll(LEGACY_EXTRA_PROJECT_NAME_OPTIONS);
    }
    if (!matchMode) {
      // 非兼容模式继续使用正式事实表的统一候选清洗规则，不继承老平台“无需标注”等特殊口径。
      return OptionItemResponseFactory.fromLegacyBusinessValues(rawValues);
    }
    List<String> visibleValues = new ArrayList<>();
    for (String rawValue : rawValues) {
      String value = TextQuerySupport.trimToNull(rawValue);
      if (value == null || "未标注项目名".equals(value)) {
        continue;
      }
      for (String part : value.split("\\s+&\\s+")) {
        String candidate = TextQuerySupport.trimToNull(part);
        if (candidate != null && !candidate.startsWith("未设定")) {
          // 老平台明确保留“无需标注”；这里只排除其项目下拉实际排除的占位值。
          visibleValues.add(candidate);
        }
      }
    }
    return OptionItemResponseFactory.fromValuesPreservingOrder(visibleValues, TextQuerySupport::trimToNull);
  }

  List<OptionItemResponse> toCodeReviewRepositoryNameOptions(boolean matchMode, List<String> values) {
    List<String> visibleValues = new ArrayList<>(
        values.stream().filter(repositoryName -> !isHiddenCodeReviewRepositoryName(repositoryName)).toList());
    if (matchMode) {
      //兼容模式-MatchMode：仅老平台兼容读路径补充 CrownCAD 默认仓库；
      //非兼容模式必须只展示正式事实表中的仓库，避免兼容默认值污染新平台数据源。
      visibleValues.add(0, "CrownCAD");
    }
    return OptionItemResponseFactory.fromLegacyBusinessValues(visibleValues);
  }

  private String projectOptionLabel(CodeReviewIllegalRecordFilterProjectOption project) {
    String name = TextQuerySupport.trimToNull(project.projectName());
    String projectId = String.valueOf(project.projectId());
    return name == null ? projectId : name + " / " + projectId;
  }

  //兼容模式-MatchMode
  private boolean isMatchModeCcSource(String source) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(
        TextQuerySupport.trimToNull(source) == null ? LEGACY_DEFAULT_SOURCE : source);
    return LEGACY_DEFAULT_SOURCE.equals(normalizedSource);
  }

  private boolean isHiddenCodeReviewRepositoryName(String repositoryName) {
    String normalized = TextQuerySupport.trimToNull(repositoryName);
    return normalized != null && "CC".equalsIgnoreCase(normalized);
  }
}
