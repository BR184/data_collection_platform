package com.data.collection.platform.service;

import java.util.Set;

public final class TagGroupMatchStrategyRegistry {
  public static final String SPLIT_EXACT_COMMA = "split_exact_comma";
  public static final String ARRAY_EXACT = "array_exact";
  public static final String LIKE = "like";
  public static final String EQ = "eq";

  private static final Set<String> SUPPORTED =
      Set.of(SPLIT_EXACT_COMMA, ARRAY_EXACT, LIKE, EQ);

  private TagGroupMatchStrategyRegistry() {}

  public static String normalize(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return EQ;
    }
    String lower = normalized.toLowerCase(java.util.Locale.ROOT);
    return SUPPORTED.contains(lower) ? lower : EQ;
  }
}
