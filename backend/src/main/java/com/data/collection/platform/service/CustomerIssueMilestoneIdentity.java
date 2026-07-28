package com.data.collection.platform.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 客户里程碑显示值与稳定业务键之间的唯一转换规则。 */
public final class CustomerIssueMilestoneIdentity {
  private static final Pattern CC_VERSION_PATTERN =
      Pattern.compile("^CC\\s*([0-9]{4})\\s*R\\s*([0-9]+)$", Pattern.CASE_INSENSITIVE);

  private CustomerIssueMilestoneIdentity() {}

  /**
   * 将真实里程碑值转换为稳定业务键；标准 CC 版本忽略内部空白和大小写。
   *
   * @param sourceValue GitLab 里程碑真实值
   * @return 稳定业务键；空值返回空字符串
   */
  public static String businessKey(String sourceValue) {
    String normalized = TextQuerySupport.trimToNull(sourceValue);
    if (normalized == null) {
      return "";
    }
    Matcher matcher = CC_VERSION_PATTERN.matcher(normalized);
    if (!matcher.matches()) {
      return normalized;
    }
    return ("CC" + matcher.group(1) + "R" + matcher.group(2)).toUpperCase(Locale.ROOT);
  }
}
