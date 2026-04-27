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
    List<String> horizontalTableHeader = null;
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
      List<String> tableCells = splitMarkdownTableCells(line);
      if (tableCells != null) {
        if (isHorizontalTableHeader(tableCells)) {
          horizontalTableHeader = tableCells;
          continue;
        }
        if (isTableSeparator(tableCells.get(0))) {
          continue;
        }
        if (horizontalTableHeader != null) {
          ParsedIntegrationNote current =
              applyHorizontalTableRow(
                  horizontalTableHeader,
                  tableCells,
                  new ParsedIntegrationNote(
                      functionName,
                      executor,
                      executeCase,
                      passCase,
                      notPassCase,
                      notPassCaseNow,
                      problemCase,
                      exceptionCount));
          functionName = current.functionName();
          executor = current.executor();
          executeCase = current.executeCase();
          passCase = current.passCase();
          notPassCase = current.notPassCase();
          notPassCaseNow = current.notPassCaseNow();
          problemCase = current.problemCase();
          exceptionCount = current.exceptionCount();
          continue;
        }
      }
      KeyValue keyValue = splitKeyValue(line);
      if (keyValue == null) {
        continue;
      }
      String key = keyValue.key();
      String value = keyValue.value();
      ParsedIntegrationNote current =
          applyKeyValue(
              key,
              value,
              new ParsedIntegrationNote(
                  functionName,
                  executor,
                  executeCase,
                  passCase,
                  notPassCase,
                  notPassCaseNow,
                  problemCase,
                  exceptionCount));
      functionName = current.functionName();
      executor = current.executor();
      executeCase = current.executeCase();
      passCase = current.passCase();
      notPassCase = current.notPassCase();
      notPassCaseNow = current.notPassCaseNow();
      problemCase = current.problemCase();
      exceptionCount = current.exceptionCount();
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
    List<String> cells = splitMarkdownTableCells(line);
    if (cells == null) {
      return null;
    }
    if (cells.size() < 2 || isTableSeparator(cells.get(0)) || isTableHeader(cells)) {
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
    return cells.stream().anyMatch(cell -> IntegrationTestFactRules.matchesKey(cell, "执行用例总数", "执行用例数"))
        && cells.stream().anyMatch(cell -> IntegrationTestFactRules.matchesKey(cell, "通过用例数", "本次通过用例数"))
        && cells.stream().anyMatch(cell -> IntegrationTestFactRules.matchesKey(cell, "本次未通过用例数", "本次未通过"));
  }

  private static boolean isTableSeparator(String value) {
    return value.replace("-", "").replace(":", "").trim().isEmpty();
  }

  private static boolean isTableHeader(List<String> cells) {
    return cells.size() >= 2 && "字段".equals(cells.get(0)) && "值".equals(cells.get(1));
  }

  private static ParsedIntegrationNote applyHorizontalTableRow(
      List<String> header, List<String> row, ParsedIntegrationNote current) {
    ParsedIntegrationNote result = current;
    int size = Math.min(header.size(), row.size());
    for (int index = 0; index < size; index++) {
      result = applyKeyValue(header.get(index), row.get(index), result);
    }
    return result;
  }

  private static ParsedIntegrationNote applyKeyValue(
      String key, String value, ParsedIntegrationNote current) {
    if (IntegrationTestFactRules.matchesKey(key, "功能标签")) {
      return current;
    }
    if (IntegrationTestFactRules.matchesKey(key, "功能")) {
      return new ParsedIntegrationNote(
          value,
          current.executor(),
          current.executeCase(),
          current.passCase(),
          current.notPassCase(),
          current.notPassCaseNow(),
          current.problemCase(),
          current.exceptionCount());
    }
    if (IntegrationTestFactRules.matchesKey(key, "执行人")) {
      return new ParsedIntegrationNote(
          current.functionName(),
          value,
          current.executeCase(),
          current.passCase(),
          current.notPassCase(),
          current.notPassCaseNow(),
          current.problemCase(),
          current.exceptionCount());
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
    if (IntegrationTestFactRules.matchesKey(key, "本次通过用例数", "通过用例数", "通过用例")) {
      return current.withPassCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "未通过用例数", "未通过用例")) {
      return current.withNotPassCaseNow(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "问题用例数", "问题用例")) {
      return current.withProblemCase(IntegrationTestFactRules.parseNumericValue(value));
    }
    if (IntegrationTestFactRules.matchesKey(key, "用例外问题数", "例外问题数")) {
      return current.withExceptionCount(IntegrationTestFactRules.parseNumericValue(value));
    }
    return current;
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
