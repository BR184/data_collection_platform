package com.data.collection.platform.domain.customerissue;

import java.util.Collection;
import java.util.List;

/**
 * 客户需求身份纯规则（R1 已冻结）。
 *
 * <p>{@code isRequirement = labels 精确包含“需求” OR labels 精确包含“类别：建议”}。只认标签成员关系，
 * 不读取标题、描述、评论、reason_category 或 category 的模糊包含；不接受“类型：建议”等别名。
 */
public final class CustomerRequirementRules {
  public static final String REQUIREMENT_LABEL = "需求";
  public static final String SUGGESTION_LABEL = "类别：建议";

  private CustomerRequirementRules() {}

  /**
   * 判断标签集合是否命中需求身份。
   *
   * @param labelTitles 当前有效原始标签集合（精确成员）
   * @return true 表示该议题属于客户需求集合 N
   */
  public static boolean isCustomerRequirement(Collection<String> labelTitles) {
    if (labelTitles == null || labelTitles.isEmpty()) {
      return false;
    }
    return labelTitles.contains(REQUIREMENT_LABEL) || labelTitles.contains(SUGGESTION_LABEL);
  }

  /** 从标签列表派生需求布尔；非客户项目由调用方强制 false。 */
  public static boolean derive(List<String> labelTitles, boolean customerProject) {
    return customerProject && isCustomerRequirement(labelTitles);
  }
}
