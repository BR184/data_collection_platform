package com.data.collection.platform.service;

final class ReviewDataModuleNameSupport {
  private ReviewDataModuleNameSupport() {}

  static String normalize(String moduleName) {
    // 评审模块是用户录入/历史导入的业务原值；只清理空白，不删除“模块”等合法后缀。
    return TextQuerySupport.normalizeDisplay(moduleName);
  }
}
