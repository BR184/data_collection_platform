package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

final class ReviewDataSearchIndexSupport {
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
