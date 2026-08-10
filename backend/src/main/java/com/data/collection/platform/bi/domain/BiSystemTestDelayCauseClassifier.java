package com.data.collection.platform.bi.domain;

import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** BI 自有、可版本化的七类系统测试延期原因规则。 */
public final class BiSystemTestDelayCauseClassifier {
  public static final String RULE_VERSION = "bi-system-test-delay-cause-v1";
  private static final String LABEL_PREFIX = "延期原因";
  private static final List<String> KNOWN_CAUSES =
      List.of("技术卡点", "方案卡点", "资源卡点", "数据异常", "算法问题", "机制问题", "计算效率");
  private static final Set<String> KNOWN_CAUSE_SET = Set.copyOf(KNOWN_CAUSES);
  private static final Pattern MEMBER_SEPARATOR = Pattern.compile("[、，,&]");
  private static final Pattern LABEL_SEPARATOR = Pattern.compile("[,，、;；\\s]+");

  /**
   * 从事实字段和历史标签中提取七类延期原因；无匹配时返回显式未归类成员。
   *
   * @param delayCause 事实层延期原因
   * @param delayReason 历史兼容原因文本
   * @param labelsText 议题标签文本
   * @return 去重且保持来源首次出现顺序的原因维度
   */
  public List<BiSourceDimension> classify(
      String delayCause,
      String delayReason,
      String labelsText) {
    Set<String> values = new LinkedHashSet<>();
    addKnown(values, delayCause);
    addKnown(values, delayReason);
    if (hasText(labelsText)) {
      for (String rawLabel : LABEL_SEPARATOR.split(labelsText.strip())) {
        String label = normalize(rawLabel);
        if (label == null) {
          continue;
        }
        addKnown(values, label);
        String prefixed = prefixedValue(label);
        if (prefixed != null) {
          addKnown(values, prefixed);
        }
      }
    }
    if (values.isEmpty()) {
      return List.of(BiSourceDimension.unknown("未归类延期原因"));
    }
    return values.stream().map(BiSourceDimension::identified).toList();
  }

  private void addKnown(Set<String> target, String rawValue) {
    String value = normalize(rawValue);
    if (value == null) {
      return;
    }
    for (String member : MEMBER_SEPARATOR.split(value)) {
      String normalized = normalize(member);
      if (KNOWN_CAUSE_SET.contains(normalized)) {
        target.add(normalized);
      }
    }
  }

  private String prefixedValue(String label) {
    int chineseColon = label.indexOf('：');
    int asciiColon = label.indexOf(':');
    int separatorIndex = chineseColon < 0
        ? asciiColon
        : asciiColon < 0 ? chineseColon : Math.min(chineseColon, asciiColon);
    if (separatorIndex <= 0 || !LABEL_PREFIX.equals(label.substring(0, separatorIndex).strip())) {
      return null;
    }
    return label.substring(separatorIndex + 1);
  }

  private boolean hasText(String value) {
    return normalize(value) != null;
  }

  private String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
