package com.data.collection.platform.service;

/**
 * “问题调研情况说明”响应模板标题的唯一识别口径。
 *
 * <p>只接受备注首行（跳过前导空白行）为 {@code # 问题调研情况说明} 或旧标题
 * {@code 问题调研情况说明}；正文其它位置提及该词不构成模板。响应存在性、模板字段解析与
 * 首次响应时间 SQL 共用本口径，避免“识别为已响应但没有首次响应时间”的分叉。
 */
final class IssueResponseTemplateTitleRules {
  static final String TEMPLATE_TITLE = "问题调研情况说明";
  static final String HEADING_TITLE = "# " + TEMPLATE_TITLE;
  private static final String NOTE_SEPARATOR = "\\R---\\R";

  private IssueResponseTemplateTitleRules() {}

  /** 判断单条评论是否是响应模板（标题位于首个非空行）。 */
  static boolean isTemplateNote(String note) {
    if (note == null || note.isBlank()) {
      return false;
    }
    String[] lines = note.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      return HEADING_TITLE.equals(trimmed) || TEMPLATE_TITLE.equals(trimmed);
    }
    return false;
  }

  /** 判断聚合备注中是否存在任何一份响应模板。 */
  static boolean containsTemplateNote(String notesText) {
    if (notesText == null || notesText.isBlank()) {
      return false;
    }
    for (String note : notesText.split(NOTE_SEPARATOR)) {
      if (isTemplateNote(note)) {
        return true;
      }
    }
    return false;
  }
}
