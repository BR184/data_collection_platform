package com.data.collection.platform.service;

import java.util.List;

public record SystemTestLegacyCauseExportFields(
    String fixStatus,
    String majorCause,
    String secondCause,
    String specificReason,
    String modification,
    String causedByOther,
    String effectFunction,
    String hasTested,
    String potentialImpact,
    String relationTableUpdated) {

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

  public static SystemTestLegacyCauseExportFields fromReasonText(String reasonText) {
    return new SystemTestLegacyCauseExportFields(
        fixStatus(reasonText),
        majorCause(reasonText),
        secondCause(reasonText),
        between(reasonText, "具体原因, 请描述：", "4、修改方案："),
        between(reasonText, "4、修改方案：", "5、是否由修改其他缺陷引起"),
        between(reasonText, "是否由修改其他缺陷引起", "6、修改该缺陷可能影响的功能"),
        between(reasonText, "修改该缺陷可能影响的功能：", "7、是否对可能影响的功能进行了测试"),
        between(reasonText, "7、是否对可能影响的功能进行了测试", "8、有无遗留问题或潜在的影响？"),
        between(reasonText, "8、有无遗留问题或潜在的影响？", "9、是否更新了关联关系表"),
        after(reasonText, "9、是否更新了关联关系表"));
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
    try {
      String afterStart = value.split(start, 2)[1];
      return afterStart.split(end, 2)[0].replace("*", "");
    } catch (Exception ignored) {
      return "--";
    }
  }

  private static String after(String value, String start) {
    try {
      return value.split(start, 2)[1].replace("*", "");
    } catch (Exception ignored) {
      return "--";
    }
  }

  private record CauseKeyword(String keyword, String major) {}
}
