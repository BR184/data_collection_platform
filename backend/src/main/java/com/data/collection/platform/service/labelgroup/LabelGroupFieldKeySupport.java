package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.service.TextQuerySupport;

final class LabelGroupFieldKeySupport {
  private LabelGroupFieldKeySupport() {
  }

  static String normalize(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    return switch (normalized) {
      case "模块", "模块名", "模块名称", "module", "moduleName", "moduleNames" -> "moduleName";
      case "评审负责人", "reviewOwner" -> "reviewOwner";
      case "评审专家", "reviewExpert" -> "reviewExpert";
      case "项目", "project", "projectName" -> "projectName";
      case "客户问题处理人", "customer_assignee", "issue_assignee", "assigneeName" -> "assigneeName";
      default -> normalized;
    };
  }

  static boolean same(String left, String right) {
    String normalizedLeft = normalize(left);
    String normalizedRight = normalize(right);
    return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
  }
}
