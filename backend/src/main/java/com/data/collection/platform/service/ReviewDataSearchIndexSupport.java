package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

final class ReviewDataSearchIndexSupport {
  // 评审数据搜索索引把标题、项目、模块、责任人、类型和专家合并成一份检索文档。
  // 这样列表关键词搜索可以和标题专用搜索共享同一套拼音、紧凑文本和首字母规则。
  private ReviewDataSearchIndexSupport() {}

  static TextQuerySupport.SearchIndex buildRecordIndex(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      List<String> reviewExperts) {
    String document =
        String.join(
            " ",
            Stream.of(
                    title,
                    projectName,
                    moduleName,
                    reviewOwner,
                    reviewType,
                    reviewExperts == null ? "" : String.join(" ", reviewExperts))
                .filter(Objects::nonNull)
                .toList());
    return TextQuerySupport.buildSearchIndex(document);
  }

  static List<String> keywordCandidates(String keyword) {
    String normalizedKeyword = TextQuerySupport.normalizeForMatch(keyword);
    if (normalizedKeyword == null) {
      return List.of();
    }
    TextQuerySupport.SearchIndex index = TextQuerySupport.buildSearchIndex(keyword);
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add(normalizedKeyword);
    candidates.add(index.compact());
    candidates.add(index.spell());
    return candidates.stream().filter(StringUtils::hasText).toList();
  }
}
