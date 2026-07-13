package com.data.collection.platform.service.analytics;

import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Apache ECharts option builder for the R&D quality dashboard. */
final class QualityRdDashboardChartOptions {
  private QualityRdDashboardChartOptions() {}

  static Map<String, Object> horizontalBar(
      List<QualityBoardChartRowResponse> rows,
      String seriesName,
      String color,
      PointParameters pointParameters) {
    List<String> categories = rows.stream().map(QualityBoardChartRowResponse::name).toList();
    List<Map<String, Object>> points = rows.stream()
        .map(row -> point(
            row.value(),
            pointParameters.pointPrefix() + ":" + row.name(),
            pointParameters.paramsFor(row.name())))
        .toList();
    Map<String, Object> option = baseHorizontalOption(categories);
    option.put(
        "series",
        List.of(Map.of(
            "name", seriesName,
            "type", "bar",
            "barMaxWidth", 26,
            "data", points,
            "itemStyle", Map.of("color", color, "borderRadius", List.of(0, 5, 5, 0)),
            "emphasis", Map.of("focus", "series"))));
    addDataZoom(option, rows.size());
    return option;
  }

  static Map<String, Object> stackedSeverity(
      List<QualityBoardFixUserSeverityRowResponse> rows,
      String projectName) {
    List<String> categories = rows.stream().map(QualityBoardFixUserSeverityRowResponse::name).toList();
    Map<String, Object> option = baseHorizontalOption(categories);
    option.put("legend", Map.of("top", 0, "left", 0));
    option.put("grid", grid(44));
    List<Map<String, Object>> series = new ArrayList<>();
    series.add(severitySeries(rows, "一级缺陷", "level1", "#ef4444", projectName));
    series.add(severitySeries(rows, "二级缺陷", "level2", "#f59e0b", projectName));
    series.add(severitySeries(rows, "三级缺陷", "level3", "#3b82f6", projectName));
    series.add(severitySeries(rows, "建议类", "suggestion", "#94a3b8", projectName));
    option.put("series", series);
    addDataZoom(option, rows.size());
    return option;
  }

  private static Map<String, Object> baseHorizontalOption(List<String> categories) {
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("animationDuration", 420);
    option.put("tooltip", Map.of("trigger", "axis", "axisPointer", Map.of("type", "shadow")));
    option.put("grid", grid(12));
    option.put(
        "xAxis",
        Map.of(
            "type", "value",
            "minInterval", 1,
            "axisLine", Map.of("show", false),
            "splitLine", Map.of("lineStyle", Map.of("color", "#eef2f7"))));
    option.put(
        "yAxis",
        Map.of(
            "type", "category",
            "data", categories,
            "axisLine", Map.of("show", false),
            "axisTick", Map.of("show", false),
            "axisLabel", Map.of("color", "#475569", "width", 132, "overflow", "truncate")));
    return option;
  }

  private static Map<String, Object> grid(int top) {
    return Map.of("top", top, "left", 16, "right", 24, "bottom", 28, "containLabel", true);
  }

  private static void addDataZoom(Map<String, Object> option, int rowCount) {
    if (rowCount <= 8) {
      return;
    }
    int end = Math.max(1, (int) Math.floor(800D / rowCount));
    option.put(
        "dataZoom",
        List.of(
            Map.of("type", "inside", "yAxisIndex", 0, "start", 0, "end", end),
            Map.of(
                "type", "slider",
                "yAxisIndex", 0,
                "right", 2,
                "width", 8,
                "start", 0,
                "end", end,
                "showDetail", false)));
  }

  private static Map<String, Object> severitySeries(
      List<QualityBoardFixUserSeverityRowResponse> rows,
      String name,
      String valueKey,
      String color,
      String projectName) {
    List<Map<String, Object>> data = rows.stream()
        .map(row -> point(
            severityValue(row, valueKey),
            "fix-user:" + row.name() + ":" + valueKey,
            Map.of(
                "projectName", projectName,
                "fixUser", row.name(),
                "severityLevel", severityLevel(valueKey))))
        .toList();
    return Map.of(
        "name", name,
        "type", "bar",
        "stack", "severity",
        "barMaxWidth", 28,
        "data", data,
        "itemStyle", Map.of("color", color),
        "emphasis", Map.of("focus", "series"));
  }

  private static int severityValue(QualityBoardFixUserSeverityRowResponse row, String valueKey) {
    return switch (valueKey) {
      case "level1" -> row.level1();
      case "level2" -> row.level2();
      case "level3" -> row.level3();
      case "suggestion" -> row.suggestion();
      default -> throw new IllegalArgumentException("Unsupported severity value: " + valueKey);
    };
  }

  private static String severityLevel(String valueKey) {
    return switch (valueKey) {
      case "level1" -> "LEVEL1";
      case "level2" -> "LEVEL2";
      case "level3" -> "LEVEL3";
      case "suggestion" -> "SUGGESTION";
      default -> throw new IllegalArgumentException("Unsupported severity value: " + valueKey);
    };
  }

  private static Map<String, Object> point(
      Number value, String pointKey, Map<String, String> detailParams) {
    Map<String, Object> point = new LinkedHashMap<>();
    point.put("value", value == null ? 0D : value);
    point.put("pointKey", pointKey);
    point.put("detailParams", detailParams);
    return point;
  }

  record PointParameters(
      String pointPrefix,
      Map<String, String> commonParams,
      String pointParameterKey) {
    PointParameters {
      commonParams = commonParams == null ? Map.of() : Map.copyOf(commonParams);
    }

    Map<String, String> paramsFor(String pointName) {
      Map<String, String> params = new LinkedHashMap<>(commonParams);
      if (pointParameterKey != null && !pointParameterKey.isBlank()) {
        params.put(pointParameterKey, pointName);
      }
      return Map.copyOf(params);
    }
  }
}
