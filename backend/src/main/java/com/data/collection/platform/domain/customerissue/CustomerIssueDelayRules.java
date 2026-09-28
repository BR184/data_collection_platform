package com.data.collection.platform.domain.customerissue;

import java.util.List;
import java.util.Locale;

/**
 * 客户问题延期范围纯规则（L 集合）与优先级桶选择。
 *
 * <p>沿现有客户延期页：建议类排除、GitLab 接口异常排除、开放状态、合法优先级桶。缺失优先级不进入
 * P 桶及总计，不补 P3。优先级匹配保留"归一化文本包含 P1/P2/P3"的历史语义，不得借抽取改成另一种判断。
 */
public final class CustomerIssueDelayRules {
  public static final String P1 = "P1";
  public static final String P2 = "P2";
  public static final String P3 = "P3";
  /** GitLab 接口异常在非法原因中的标记。 */
  public static final String GITLAB_API_ERROR = "GitLab接口报错";

  private CustomerIssueDelayRules() {}

  /** 优先级桶标签；未命中任一合法桶时返回空串。 */
  public static String priorityBucket(String priorityLevel) {
    String normalized = normalize(priorityLevel);
    if (normalized.contains("p1")) {
      return P1;
    }
    if (normalized.contains("p2")) {
      return P2;
    }
    if (normalized.contains("p3")) {
      return P3;
    }
    return "";
  }

  /** 是否命中指定优先级桶。 */
  public static boolean matchesLegacyPriority(String priorityLevel, String expectedPriority) {
    return normalize(priorityLevel).contains(normalize(expectedPriority));
  }

  /** 是否具备合法优先级桶（P1/P2/P3）。 */
  public static boolean hasLegacyPriorityBucket(String priorityLevel) {
    return !priorityBucket(priorityLevel).isEmpty();
  }

  /** 延期资格（L 集合）：常规缺陷 + 无接口异常 + open + 至少一种延期。 */
  public static boolean isDelayEligible(
      boolean regularMetricIssue,
      boolean hasGitLabApiError,
      boolean open,
      boolean responseDelayed,
      boolean resolveDelayed) {
    return regularMetricIssue
        && !hasGitLabApiError
        && open
        && (responseDelayed || resolveDelayed);
  }

  /** 非法原因中是否含 GitLab 接口报错（先去除空格再整体比较）。 */
  public static boolean hasGitLabApiError(String illegalReason, List<String> illegalReasons) {
    if (matchesGitLabApiError(illegalReason)) {
      return true;
    }
    return illegalReasons != null && illegalReasons.stream().anyMatch(CustomerIssueDelayRules::matchesGitLabApiError);
  }

  private static boolean matchesGitLabApiError(String value) {
    return GITLAB_API_ERROR.equals(value == null ? "" : value.replace(" ", "").trim());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
