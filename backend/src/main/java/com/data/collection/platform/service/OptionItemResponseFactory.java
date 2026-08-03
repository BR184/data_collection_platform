package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class OptionItemResponseFactory {
  enum SortPolicy {
    LABEL_ASCENDING,
    SOURCE_ORDER
  }

  private OptionItemResponseFactory() {
  }

  public static <T> List<OptionItemResponse> from(Collection<T> rows, Function<T, String> extractor, Function<String, String> normalizer) {
    return from(rows.stream().map(extractor).toList(), normalizer);
  }

  public static List<OptionItemResponse> from(Collection<String> values, Function<String, String> normalizer) {
    return fromValues(values, normalizer, Function.identity());
  }

  public static List<OptionItemResponse> fromValues(
      Collection<String> values, Function<String, String> normalizer, Function<String, String> labeler) {
    Set<String> normalized = values.stream()
        .map(normalizer)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return buildOptions(normalized, labeler, SortPolicy.LABEL_ASCENDING);
  }

  public static List<OptionItemResponse> fromValuesPreservingOrder(
      Collection<String> values, Function<String, String> normalizer) {
    Set<String> normalized =
        values.stream()
            .map(normalizer)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return buildOptions(normalized, Function.identity(), SortPolicy.SOURCE_ORDER);
  }

  public static List<OptionItemResponse> fromLegacyBusinessValues(Collection<String> values) {
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      String text = TextQuerySupport.trimToNull(value);
      if (text == null) {
        continue;
      }
      for (String part : text.split("\\s+&\\s+")) {
        String candidate = TextQuerySupport.trimToNull(part);
        if (candidate != null && !isLegacyPlaceholder(candidate)) {
          normalized.add(candidate);
        }
      }
    }
    // 未来标签组排序扩展点：
    // 老平台动态下拉以“数据源首次出现顺序”为准；一周后接入“基于标签组手动指定候选内容/顺序”时，
    // 应在 buildOptions/applySortPolicy 这一层叠加“标签组成员序号优先”的 SortPolicy，
    // 不要在各页面、各 service 中散落手写 sort 或兼容分支。
    return buildOptions(normalized, Function.identity(), SortPolicy.SOURCE_ORDER);
  }

  /**
   * 根据测试状态成员构建按显示文本排序的下拉候选。
   *
   * @param values 事实层中的原始测试状态文本
   * @return 每个状态成员一条候选值
   */
  public static List<OptionItemResponse> fromIssueStatusMembers(Collection<String> values) {
    return buildOptions(
        IssueStatusMembers.collectMembers(values), Function.identity(), SortPolicy.LABEL_ASCENDING);
  }

  /**
   * 根据测试状态成员构建保留数据源首次出现顺序的下拉候选。
   *
   * @param values 事实层中的原始测试状态文本
   * @return 每个状态成员一条候选值
   */
  public static List<OptionItemResponse> fromIssueStatusMembersPreservingOrder(
      Collection<String> values) {
    return buildOptions(
        IssueStatusMembers.collectMembers(values), Function.identity(), SortPolicy.SOURCE_ORDER);
  }

  /**
   * 根据事实层延期原因成员构建按显示文本排序的候选。
   *
   * @param values 事实层中的延期原因组合文本
   * @return 每个延期原因成员一条候选值
   */
  public static List<OptionItemResponse> fromDelayCauseMembers(Collection<String> values) {
    return buildOptions(
        IssueDelayCauseMembers.collectMembers(values), Function.identity(), SortPolicy.LABEL_ASCENDING);
  }

  private static List<OptionItemResponse> buildOptions(
      Collection<String> values, Function<String, String> labeler, SortPolicy sortPolicy) {
    return applySortPolicy(
            values.stream()
                .map(value -> new OptionItemResponse(labeler.apply(value), value)),
            sortPolicy)
        .toList();
  }

  private static java.util.stream.Stream<OptionItemResponse> applySortPolicy(
      java.util.stream.Stream<OptionItemResponse> options, SortPolicy sortPolicy) {
    if (sortPolicy == SortPolicy.SOURCE_ORDER) {
      return options;
    }
    return options.sorted(Comparator.comparing(OptionItemResponse::label, String::compareToIgnoreCase));
  }

  private static boolean isLegacyPlaceholder(String value) {
    return value.startsWith("未设定")
        || value.startsWith("未标注")
        || value.startsWith("未识别")
        || value.startsWith("未标记")
        || "无需标注".equals(value)
        || "GitLab接口报错".equals(value);
  }
}
