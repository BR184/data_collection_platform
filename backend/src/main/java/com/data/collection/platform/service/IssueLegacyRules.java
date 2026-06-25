package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

final class IssueLegacyRules {
  private IssueLegacyRules() {
  }

  static boolean isLegacy(
      List<String> labels,
      boolean closed,
      LocalDateTime createdAt,
      LocalDateTime phaseStartAt) {
    // 优先按标签判定（与老平台一致）
    // 老平台逻辑：bug_status LIKE '%历史遗留%'
    if (IssueLabelRules.isLegacyByLabel(labels)) {
      return true;
    }

    // 降级：如果没有标签，按时间计算
    // 议题未关闭 且 创建时间早于测试阶段开始时间 = 历史遗留
    return !closed && createdAt != null && phaseStartAt != null && createdAt.isBefore(phaseStartAt);
  }
}
