package com.data.collection.platform.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

final class IntegrationTestNoteParser {
  private IntegrationTestNoteParser() {}

  // 老平台同时存在键值、标题式字段、纵向表格和横向表格；这里只解析明确的集成测试段落。
  static ParsedIntegrationNote parse(String noteText) {
    if (!StringUtils.hasText(noteText)) {
      return ParsedIntegrationNote.empty();
    }
    boolean started = false;
    ParsedIntegrationNote parsed = ParsedIntegrationNote.empty();
    List<String> horizontalHeader = null;
    for (String rawLine : noteText.replace("\r\n", "\n").split("\n")) {
      String raw = TextQuerySupport.trimToNull(rawLine);
      if (raw == null) {
        continue;
      }
      boolean heading = isMarkdownHeading(raw);
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
      if (heading && !line.contains("集成测试数据") && !isIntegrationFieldLine(line)) {
        break;
      }
      List<String> cells = splitMarkdownTableCells(line);
      if (cells != null) {
        if (isHorizontalTableHeader(cells)) {
          horizontalHeader = cells;
          continue;
        }
        if (!cells.isEmpty() && isTableSeparator(cells.getFirst())) {
          continue;
        }
        if (horizontalHeader != null) {
          parsed = mergeHorizontalTableRow(horizontalHeader, cells, parsed);
          continue;
        }
      }
      KeyValue keyValue = splitKeyValue(line);
      if (keyValue != null) {
        parsed = applyKeyValue(keyValue.key(), keyValue.value(), parsed);
      }
    }
    return parsed;
  }

  private static String stripMarkdownPrefix(String value) {
    String result = value == null ? "" : value.trim();
    while (result.startsWith("#") || result.startsWith("-") || result.startsWith("*")) {
      result = result.substring(1).trim();
    }
    return result;
  }

  private static boolean isMarkdownHeading(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized != null && normalized.matches("#{1,6}\\s+.*");
  }

  private static boolean isIntegrationFieldLine(String line) {
    KeyValue keyValue = splitKeyValue(line);
    String key = keyValue == null ? line : keyValue.key();
    return IntegrationTestFactRules.matchesKey(
        key,
        "功能标签",
        "功能",
        "执行人",
        "执行用例总数",
        "执行用例数",
        "初始未通过用例数",
        "初始未通过",
        "本次未通过用例数",
        "本次未通过",
        "本次问题用例数",
        "本次问题用例",
        "本次通过用例数",
        "通过用例数",
        "通过用例",
        "未通过用例数",
        "未通过用例",
        "问题用例数",
        "问题用例",
        "用例外问题数",
        "例外问题数");
  }

  private static KeyValue splitKeyValue(String line) {
    KeyValue tableValue = splitMarkdownTableRow(line);
    if (tableValue != null) {
      return tableValue;
    }
    int separatorIndex = firstSeparatorIndex(line);
    if (separatorIndex < 0) {
      return null;
    }
    String key = TextQuerySupport.trimToNull(line.substring(0, separatorIndex));
    String value = TextQuerySupport.trimToNull(line.substring(separatorIndex + 1));
    return key == null || value == null ? null : new KeyValue(key, value);
  }

  private static int firstSeparatorIndex(String line) {
    int result = -1;
    for (char separator : new char[] {'：', ':', '=', '＝'}) {
      int candidate = line.indexOf(separator);
      if (candidate >= 0 && (result < 0 || candidate < result)) {
        result = candidate;
      }
    }
    return result;
  }

  private static KeyValue splitMarkdownTableRow(String line) {
    List<String> cells = splitMarkdownTableCells(line);
    if (cells == null
        || cells.size() < 2
        || isTableSeparator(cells.getFirst())
        || isTableHeader(cells)) {
      return null;
    }
    return new KeyValue(cells.get(0), cells.get(1));
  }

  private static List<String> splitMarkdownTableCells(String line) {
    String normalized = TextQuerySupport.trimToNull(line);
    if (normalized == null || !normalized.startsWith("|") || !normalized.endsWith("|")) {
      return null;
    }
    return Arrays.stream(normalized.split("\\|"))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .toList();
  }

  private static boolean isHorizontalTableHeader(List<String> cells) {
    return cells.stream()
            .anyMatch(
                cell ->
                    IntegrationTestFactRules.matchesKey(
                        cell, "执行用例总数", "执行用例数"))
        && cells.stream()
            .anyMatch(
                cell ->
                    IntegrationTestFactRules.matchesKey(
                        cell, "通过用例数", "本次通过用例数"))
        && cells.stream()
            .anyMatch(
                cell ->
                    IntegrationTestFactRules.matchesKey(
                        cell, "本次未通过用例数", "本次未通过", "未通过用例数"));
  }

  private static boolean isTableSeparator(String value) {
    return value.replace("-", "").replace(":", "").trim().isEmpty();
  }

  private static boolean isTableHeader(List<String> cells) {
    return cells.size() >= 2 && "字段".equals(cells.get(0)) && "值".equals(cells.get(1));
  }

  private static ParsedIntegrationNote mergeHorizontalTableRow(
      List<String> header, List<String> row, ParsedIntegrationNote current) {
    ParsedIntegrationNote rowValue = ParsedIntegrationNote.empty();
    int size = Math.min(header.size(), row.size());
    for (int index = 0; index < size; index++) {
      rowValue = applyKeyValue(header.get(index), row.get(index), rowValue);
    }
    return new ParsedIntegrationNote(
        mergeText(current.functionName(), rowValue.functionName()),
        mergeText(current.executor(), rowValue.executor()),
        sum(current.executeCase(), rowValue.executeCase()),
        sum(current.passCase(), rowValue.passCase()),
        sum(current.notPassCase(), rowValue.notPassCase()),
        sum(current.notPassCaseNow(), rowValue.notPassCaseNow()),
        sum(current.problemCase(), rowValue.problemCase()),
        sum(current.exceptionCount(), rowValue.exceptionCount()));
  }

  private static ParsedIntegrationNote applyKeyValue(
      String key, String value, ParsedIntegrationNote current) {
    if (IntegrationTestFactRules.matchesKey(key, "功能标签")) {
      return current;
    }
    if (IntegrationTestFactRules.matchesKey(key, "功能")) {
      return current.withFunctionName(value);
    }
    if (IntegrationTestFactRules.matchesKey(key, "执行人")) {
      return current.withExecutor(value);
    }
    if (IntegrationTestFactRules.matchesKey(key, "执行用例总数", "执行用例数")) {
      return current.withExecuteCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "初始未通过用例数", "初始未通过")) {
      return current.withNotPassCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "本次未通过用例数", "本次未通过")) {
      return current.withNotPassCaseNow(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "本次问题用例数", "本次问题用例")) {
      return current.withProblemCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "未通过用例数", "未通过用例")) {
      return current.withLegacyNotPassCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "本次通过用例数", "通过用例数", "通过用例")) {
      return current.withPassCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "问题用例数", "问题用例")) {
      return current.withProblemCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "用例外问题数", "例外问题数")) {
      return current.withExceptionCount(IntegrationTestFactRules.parseNumericValue(value));
    }
    return current;
  }

  private static String mergeText(String current, String next) {
    Set<String> values = new LinkedHashSet<>();
    addTextValues(values, current);
    addTextValues(values, next);
    return values.isEmpty() ? null : String.join(", ", values);
  }

  private static void addTextValues(Set<String> values, String text) {
    String normalized = TextQuerySupport.trimToNull(text);
    if (normalized == null) {
      return;
    }
    for (String value : normalized.split("[,，、]")) {
      String item = TextQuerySupport.trimToNull(value);
      if (item != null) {
        values.add(item);
      }
    }
  }

  private static Integer sum(Integer current, Integer next) {
    if (current == null) {
      return next;
    }
    return next == null ? current : current + next;
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

    static ParsedIntegrationNote empty() {
      return new ParsedIntegrationNote(null, null, null, null, null, null, null, null);
    }

    private ParsedIntegrationNote withFunctionName(String value) {
      return new ParsedIntegrationNote(
          value, executor, executeCase, passCase, notPassCase, notPassCaseNow, problemCase, exceptionCount);
    }

    private ParsedIntegrationNote withExecutor(String value) {
      return new ParsedIntegrationNote(
          functionName, value, executeCase, passCase, notPassCase, notPassCaseNow, problemCase, exceptionCount);
    }

    private ParsedIntegrationNote withExecuteCase(Integer value) {
      return new ParsedIntegrationNote(
          functionName, executor, value, passCase, notPassCase, notPassCaseNow, problemCase, exceptionCount);
    }

    private ParsedIntegrationNote withPassCase(Integer value) {
      return new ParsedIntegrationNote(
          functionName, executor, executeCase, value, notPassCase, notPassCaseNow, problemCase, exceptionCount);
    }

    private ParsedIntegrationNote withNotPassCase(Integer value) {
      return new ParsedIntegrationNote(
          functionName, executor, executeCase, passCase, value, notPassCaseNow, problemCase, exceptionCount);
    }

    private ParsedIntegrationNote withNotPassCaseNow(Integer value) {
      return new ParsedIntegrationNote(
          functionName, executor, executeCase, passCase, notPassCase, value, problemCase, exceptionCount);
    }

    private ParsedIntegrationNote withLegacyNotPassCase(Integer value) {
      return new ParsedIntegrationNote(
          functionName,
          executor,
          executeCase,
          passCase,
          notPassCase == null ? value : notPassCase,
          notPassCaseNow == null ? value : notPassCaseNow,
          problemCase,
          exceptionCount);
    }

    private ParsedIntegrationNote withProblemCase(Integer value) {
      return new ParsedIntegrationNote(
          functionName, executor, executeCase, passCase, notPassCase, notPassCaseNow, value, exceptionCount);
    }

    private ParsedIntegrationNote withExceptionCount(Integer value) {
      return new ParsedIntegrationNote(
          functionName, executor, executeCase, passCase, notPassCase, notPassCaseNow, problemCase, value);
    }
  }
}
