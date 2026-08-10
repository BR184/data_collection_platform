package com.data.collection.platform.service;

import java.util.List;
import java.util.regex.Pattern;

/** 老平台修复模板在议题明细导出中的稳定字段投影。 */
public record SystemTestLegacyCauseExportFields(
    String fixStatus,
    String majorCause,
    String secondCause,
    String specificReason,
    String modification,
    String causedByOther,
    String effectFunction,
    String knownAffectedFunction,
    String newlyIdentifiedAffectedFunction,
    String hasTested,
    String potentialImpact,
    String relationTableUpdated) {

  private static final String KNOWN_AFFECTED_FUNCTION = "已知的受影响功能";
  private static final String NEWLY_IDENTIFIED_AFFECTED_FUNCTION = "新识别的受影响功能";
  private static final String NO_OTHER_IMPACT = "无其他影响";
  private static final Pattern KNOWN_AFFECTED_FUNCTION_SELECTION =
      selectionPattern(KNOWN_AFFECTED_FUNCTION);
  private static final Pattern NEWLY_IDENTIFIED_AFFECTED_FUNCTION_SELECTION =
      selectionPattern(NEWLY_IDENTIFIED_AFFECTED_FUNCTION);
  private static final List<String> FIX_STATUS_TOKENS = List.of("已解决", "部分解决", "申请延期", "无法复现");
  private static final List<CauseKeyword> CAUSE_KEYWORDS =
      List.of(
          new CauseKeyword("精度导致约束求解异常", "精度问题"),
          new CauseKeyword("精度导致算法执行异常", "精度问题"),
          new CauseKeyword("环境配置问题", "打包问题"),
          new CauseKeyword("编译/打包/部署问题", "打包问题"),
          new CauseKeyword("新增需求问题", "需求阶段"),
          new CauseKeyword("需求理解有误", "需求阶段"),
          new CauseKeyword("新增理解偏差", "需求阶段"),
          new CauseKeyword("需求遗漏", "需求阶段"),
          new CauseKeyword("新增需求", "需求阶段"),
          new CauseKeyword("需求变更未同步", "需求阶段"),
          new CauseKeyword("功能设计遗漏", "设计问题"),
          new CauseKeyword("设计方案不合理", "设计问题"),
          new CauseKeyword("场景考虑不全", "设计问题"),
          new CauseKeyword("术语、提示不正确", "设计问题"),
          new CauseKeyword("术语、提示信息不合适", "设计问题"),
          new CauseKeyword("编码规范错误", "编码问题"),
          new CauseKeyword("功能编码遗漏", "编码问题"),
          new CauseKeyword("编码逻辑：计算与算法错误", "编码问题"),
          new CauseKeyword("编码逻辑：流程控制错误", "编码问题"),
          new CauseKeyword("编码逻辑：数据与状态处理错误", "编码问题"),
          new CauseKeyword("编码逻辑：业务逻辑错误", "编码问题"),
          new CauseKeyword("编码逻辑：集成与接口错误", "编码问题"),
          new CauseKeyword("编码逻辑错误", "编码问题"),
          new CauseKeyword("编译打包问题", "编码问题"),
          new CauseKeyword("第三方库问题", "依赖问题"),
          new CauseKeyword("算法/机制不支持", "依赖问题"),
          new CauseKeyword("未识别的前后置任务", "依赖问题"),
          new CauseKeyword("算法不支持", "依赖问题"),
          new CauseKeyword("机制不支持", "依赖问题"),
          new CauseKeyword("前置数据异常（如缺少模板文件、前置输入文件本身错误等）", "依赖问题"));

  /**
   * 从标准化修复原因文本生成老平台议题导出字段。
   *
   * @param reasonText 事实层保存的标准化修复原因文本，可为空
   * @return 字段投影；新版受影响功能选项输出“是/否”，旧模板无法判定时输出“--”
   */
  public static SystemTestLegacyCauseExportFields fromReasonText(String reasonText) {
    String rawEffectFunction =
        betweenRaw(reasonText, "修改该缺陷可能影响的功能：", "7、是否对可能影响的功能进行了测试");
    String effectFunction = withoutSelectionMarkers(rawEffectFunction);
    AffectedFunctionExportFields affectedFunctions = affectedFunctionsFromText(rawEffectFunction);
    return new SystemTestLegacyCauseExportFields(
        fixStatus(reasonText),
        majorCause(reasonText),
        secondCause(reasonText),
        between(reasonText, "具体原因, 请描述：", "4、修改方案："),
        between(reasonText, "4、修改方案：", "5、是否由修改其他缺陷引起"),
        between(reasonText, "是否由修改其他缺陷引起", "6、修改该缺陷可能影响的功能"),
        effectFunction,
        affectedFunctions.knownAffectedFunction(),
        affectedFunctions.newlyIdentifiedAffectedFunction(),
        between(reasonText, "7、是否对可能影响的功能进行了测试", "8、有无遗留问题或潜在的影响？"),
        between(reasonText, "8、有无遗留问题或潜在的影响？", "9、是否更新了关联关系表"),
        after(reasonText, "9、是否更新了关联关系表"));
  }

  /**
   * 将已截取的“受影响功能”模板内容转换为两个复选项的导出值。
   *
   * <p>事实标准化会只保留已选项，旧解析链路还可能移除选择标记，因此同时支持带
   * {@code *} 和不带标记的标准化文本。没有任何新版选项时视为旧模板，不能把未知误报为否。</p>
   *
   * @param effectFunctionText 受影响功能字段或对应模板片段
   * @return 两个稳定导出值
   */
  public static AffectedFunctionExportFields affectedFunctionsFromText(String effectFunctionText) {
    String value = effectFunctionText == null ? "" : effectFunctionText;
    boolean modernTemplate =
        value.contains(KNOWN_AFFECTED_FUNCTION)
            || value.contains(NEWLY_IDENTIFIED_AFFECTED_FUNCTION)
            || value.contains(NO_OTHER_IMPACT);
    if (!modernTemplate) {
      return new AffectedFunctionExportFields("--", "--");
    }
    boolean hasSelectionMarker = value.contains("*");
    boolean knownSelected =
        isSelected(
            value,
            KNOWN_AFFECTED_FUNCTION,
            KNOWN_AFFECTED_FUNCTION_SELECTION,
            hasSelectionMarker);
    boolean newlyIdentifiedSelected =
        isSelected(
            value,
            NEWLY_IDENTIFIED_AFFECTED_FUNCTION,
            NEWLY_IDENTIFIED_AFFECTED_FUNCTION_SELECTION,
            hasSelectionMarker);
    return new AffectedFunctionExportFields(
        knownSelected ? "是" : "否", newlyIdentifiedSelected ? "是" : "否");
  }

  private static boolean isSelected(
      String value, String option, Pattern selectionPattern, boolean hasSelectionMarker) {
    if (!hasSelectionMarker) {
      return value.contains(option);
    }
    return selectionPattern.matcher(value).find();
  }

  private static Pattern selectionPattern(String option) {
    return Pattern.compile("\\*\\s*" + Pattern.quote(option));
  }

  private static String fixStatus(String value) {
    if (value == null) {
      return "";
    }
    return FIX_STATUS_TOKENS.stream().filter(value::contains).findFirst().orElse("--");
  }

  private static String majorCause(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder result = new StringBuilder();
    for (CauseKeyword keyword : CAUSE_KEYWORDS) {
      if (value.contains(keyword.keyword())) {
        result.append(keyword.major()).append(' ');
      }
    }
    return result.toString();
  }

  private static String secondCause(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder result = new StringBuilder();
    for (CauseKeyword keyword : CAUSE_KEYWORDS) {
      if (value.contains(keyword.keyword())) {
        result.append(keyword.keyword()).append(' ');
      }
    }
    return result.toString();
  }

  private static String between(String value, String start, String end) {
    return withoutSelectionMarkers(betweenRaw(value, start, end));
  }

  private static String betweenRaw(String value, String start, String end) {
    try {
      String afterStart = value.split(start, 2)[1];
      return afterStart.split(end, 2)[0];
    } catch (Exception ignored) {
      return "--";
    }
  }

  private static String withoutSelectionMarkers(String value) {
    return value.replace("*", "");
  }

  private static String after(String value, String start) {
    try {
      return value.split(start, 2)[1].replace("*", "");
    } catch (Exception ignored) {
      return "--";
    }
  }

  /** 新版修复模板中两个受影响功能复选项的导出值。 */
  public record AffectedFunctionExportFields(
      String knownAffectedFunction, String newlyIdentifiedAffectedFunction) {}

  private record CauseKeyword(String keyword, String major) {}
}
