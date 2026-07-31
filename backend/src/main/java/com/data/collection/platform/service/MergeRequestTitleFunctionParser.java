package com.data.collection.platform.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** 从 GitLab 合并请求标题提取代码走查功能名。 */
final class MergeRequestTitleFunctionParser {
  private static final Pattern BRACKETED_FUNCTION = Pattern.compile("[\\[【]([^\\]】]*)[\\]】]");

  private MergeRequestTitleFunctionParser() {}

  static String parse(String title) {
    if (!StringUtils.hasText(title)) {
      return null;
    }
    Matcher matcher = BRACKETED_FUNCTION.matcher(title.trim());
    while (matcher.find()) {
      String functionName = matcher.group(1).trim();
      if (StringUtils.hasText(functionName)) {
        return functionName;
      }
    }
    return null;
  }
}
