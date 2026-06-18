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
    return normalized.stream()
        .map(value -> new OptionItemResponse(labeler.apply(value), value))
        .sorted(Comparator.comparing(OptionItemResponse::label, String::compareToIgnoreCase))
        .toList();
  }

  public static List<OptionItemResponse> fromLegacyBusinessValues(Collection<String> values) {
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      String text = TextQuerySupport.trimToNull(value);
      if (text == null) {
        continue;
      }
      for (String part : text.split("\\s*&\\s*")) {
        String candidate = TextQuerySupport.trimToNull(part);
        if (candidate != null && !isLegacyPlaceholder(candidate)) {
          normalized.add(candidate);
        }
      }
    }
    return normalized.stream()
        .map(value -> new OptionItemResponse(value, value))
        .sorted(Comparator.comparing(OptionItemResponse::label, String::compareToIgnoreCase))
        .toList();
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
