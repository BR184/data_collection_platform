package com.data.collection.platform.service;

final class ReviewDataModuleNameSupport {
  private static final String MODULE_SUFFIX = "模块";

  private ReviewDataModuleNameSupport() {}

  static String normalize(String moduleName) {
    String normalized = TextQuerySupport.normalizeDisplay(moduleName);
    if (normalized.endsWith(MODULE_SUFFIX) && normalized.length() > MODULE_SUFFIX.length()) {
      normalized = normalized.substring(0, normalized.length() - MODULE_SUFFIX.length()).trim();
    }
    return normalized;
  }
}
