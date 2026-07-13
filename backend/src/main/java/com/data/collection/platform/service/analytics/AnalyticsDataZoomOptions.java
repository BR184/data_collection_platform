package com.data.collection.platform.service.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared Apache ECharts data-zoom presentation for analytics dashboards.
 *
 * <p>The slider dimensions deliberately stay close to the ECharts 30px default rather than
 * shrinking to a barely visible rail. Data ranges remain owned by each dashboard provider.
 */
public final class AnalyticsDataZoomOptions {
  public static final int VERTICAL_GRID_RIGHT = 64;
  public static final int HORIZONTAL_GRID_BOTTOM = 88;

  // Match the Apache ECharts slider default so controls remain easy to grab and read.
  private static final int VERTICAL_SLIDER_WIDTH = 30;
  private static final int HORIZONTAL_SLIDER_HEIGHT = 30;
  private static final int VERTICAL_SLIDER_RIGHT = 10;
  private static final int HORIZONTAL_SLIDER_BOTTOM = 10;

  private AnalyticsDataZoomOptions() {}

  public static List<Map<String, Object>> vertical(int rowCount, int requestedEndValue) {
    return List.of(
        Map.of(
            "type", "inside",
            "yAxisIndex", 0,
            "startValue", 0,
            "endValue", endValue(rowCount, requestedEndValue)),
        slider("yAxisIndex", rowCount, requestedEndValue, true));
  }

  public static List<Map<String, Object>> horizontal(int rowCount, int requestedEndValue) {
    return List.of(
        Map.of(
            "type", "inside",
            "xAxisIndex", 0,
            "startValue", 0,
            "endValue", endValue(rowCount, requestedEndValue)),
        slider("xAxisIndex", rowCount, requestedEndValue, false));
  }

  private static Map<String, Object> slider(
      String axisIndexKey, int rowCount, int requestedEndValue, boolean vertical) {
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("type", "slider");
    option.put(axisIndexKey, 0);
    option.put("startValue", 0);
    option.put("endValue", endValue(rowCount, requestedEndValue));
    option.put("show", true);
    option.put("showDetail", false);
    if (vertical) {
      option.put("right", VERTICAL_SLIDER_RIGHT);
      option.put("width", VERTICAL_SLIDER_WIDTH);
    } else {
      option.put("bottom", HORIZONTAL_SLIDER_BOTTOM);
      option.put("height", HORIZONTAL_SLIDER_HEIGHT);
    }
    option.put("borderColor", "#cbd5e1");
    option.put("borderRadius", 6);
    option.put("backgroundColor", "#f8fafc");
    option.put("fillerColor", "rgba(37, 99, 235, 0.18)");
    option.put("dataBackground", dataBackground("#cbd5e1", "#e2e8f0", 0.58));
    option.put("selectedDataBackground", dataBackground("#60a5fa", "#bfdbfe", 0.72));
    option.put("handleSize", "110%");
    option.put("handleStyle", handleStyle("#ffffff", "#2563eb"));
    option.put("moveHandleSize", 12);
    option.put("moveHandleStyle", Map.of("color", "rgba(37, 99, 235, 0.34)"));
    option.put("emphasis", Map.of("handleStyle", handleStyle("#ffffff", "#1d4ed8")));
    return Map.copyOf(option);
  }

  private static int endValue(int rowCount, int requestedEndValue) {
    return Math.min(Math.max(0, requestedEndValue), Math.max(0, rowCount - 1));
  }

  private static Map<String, Object> dataBackground(String lineColor, String areaColor, double opacity) {
    return Map.of(
        "lineStyle", Map.of("color", lineColor, "width", 1),
        "areaStyle", Map.of("color", areaColor, "opacity", opacity));
  }

  private static Map<String, Object> handleStyle(String color, String borderColor) {
    return Map.of(
        "color", color,
        "borderColor", borderColor,
        "borderWidth", 1.5,
        "shadowBlur", 3,
        "shadowColor", "rgba(37, 99, 235, 0.18)");
  }
}
