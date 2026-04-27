package com.data.collection.platform.service;

import java.util.Arrays;
import java.util.List;
import org.springframework.util.StringUtils;

final class IntegrationTestNoteParser {
  private IntegrationTestNoteParser() {}

  static ParsedIntegrationNote parse(String noteText) {
    if (!StringUtils.hasText(noteText)) {
      return ParsedIntegrationNote.empty();
    }
    List<String> lines = List.of(noteText.replace("\r\n", "\n").split("\n"));
    boolean started = false;
    String functionName = null;
    String executor = null;
    Integer executeCase = null;
    Integer passCase = null;
    Integer notPassCase = null;
    Integer notPassCaseNow = null;
    Integer problemCase = null;
    Integer exceptionCount = null;
    for (String rawLine : lines) {
      String raw = TextQuerySupport.trimToNull(rawLine);
      if (raw == null) {
        continue;
      }
      boolean heading = raw.startsWith("## ");
      String line = TextQuerySupport.trimToNull(stripMarkdownPrefix(raw));
      if (line == null) {
        continue;
      }
      if (!started) {
        if (line.contains("集成测试数据")) {
          started = true;
        }
        continue;
      }
      if (heading && !line.contains("集成测试数据")) {
        break;
      }
      KeyValue keyValue = splitKeyValue(line);
      if (keyValue == null) {
        continue;
      }
      String key = keyValue.key();
      String value = keyValue.value();
      if (IntegrationTestFactRules.matchesKey(key, "功能标签")) {
        continue;
      }
      if (IntegrationTestFactRules.matchesKey(key, "功能")) {
        functionName = value;
      } else if (IntegrationTestFactRules.matchesKey(key, "执行人")) {
        executor = value;
      } else if (IntegrationTestFactRules.matchesKey(key, "执行用例总数", "执行用例数")) {
        executeCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "初始未通过用例数", "初始未通过")) {
        notPassCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "本次未通过用例数", "本次未通过")) {
        notPassCaseNow = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "本次问题用例数", "本次问题用例")) {
        problemCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "本次通过用例数", "通过用例数", "通过用例")) {
        passCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "未通过用例数", "未通过用例")) {
        notPassCaseNow = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "问题用例数", "问题用例")) {
        problemCase = IntegrationTestFactRules.parseNumericValue(value);
      } else if (IntegrationTestFactRules.matchesKey(key, "用例外问题数", "例外问题数")) {
        exceptionCount = IntegrationTestFactRules.parseNumericValue(value);
      }
    }
    return new ParsedIntegrationNote(
        functionName,
        executor,
        executeCase,
        passCase,
        notPassCase,
        notPassCaseNow,
        problemCase,
        exceptionCount);
  }

  private static String stripMarkdownPrefix(String value) {
    String result = value == null ? "" : value.trim();
    while (result.startsWith("#") || result.startsWith("-") || result.startsWith("*")) {
      result = result.substring(1).trim();
    }
    return result;
  }

  private static KeyValue splitKeyValue(String line) {
    KeyValue tableValue = splitMarkdownTableRow(line);
    if (tableValue != null) {
      return tableValue;
    }
    int separatorIndex = line.indexOf('：');
    if (separatorIndex < 0) {
      separatorIndex = line.indexOf(':');
    }
    if (separatorIndex < 0) {
      separatorIndex = line.indexOf('=');
    }
    if (separatorIndex < 0) {
      separatorIndex = line.indexOf('＝');
    }
    if (separatorIndex < 0) {
      return null;
    }
    String key = TextQuerySupport.trimToNull(line.substring(0, separatorIndex));
    String value = TextQuerySupport.trimToNull(line.substring(separatorIndex + 1));
    return key == null || value == null ? null : new KeyValue(key, value);
  }

  private static KeyValue splitMarkdownTableRow(String line) {
    String normalized = TextQuerySupport.trimToNull(line);
    if (normalized == null || !normalized.startsWith("|") || !normalized.endsWith("|")) {
      return null;
    }
    List<String> cells =
        Arrays.stream(normalized.split("\\|"))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
    if (cells.size() < 2 || isTableSeparator(cells.get(0)) || isTableHeader(cells)) {
      return null;
    }
    return new KeyValue(cells.get(0), cells.get(1));
  }

  private static boolean isTableSeparator(String value) {
    return value.replace("-", "").replace(":", "").trim().isEmpty();
  }

  private static boolean isTableHeader(List<String> cells) {
    return cells.size() >= 2 && "字段".equals(cells.get(0)) && "值".equals(cells.get(1));
  }

  private record KeyValue(String key, String value) {}

  record ParsedIntegrationNote(
      String functionName,
      String executor,
      Integer executeCase,
      Integer passCase,
      Integer notPassCase,
      Integer notPassCaseNow,
      Integer problemCase,
      Integer exceptionCount) {
    private static ParsedIntegrationNote empty() {
      return new ParsedIntegrationNote(null, null, null, null, null, null, null, null);
    }
  }
}
