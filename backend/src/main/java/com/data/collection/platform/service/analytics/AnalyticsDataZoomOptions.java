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
  /** Old-platform charts initially expose category indexes 0 through 10. */
  public static final int LEGACY_INITIAL_VIEWPORT_END_VALUE = 10;

  // Keep the rotated category labels, the 30px slider, and a visible lower edge separated.
  // ECharts positions the slider handle outside the rail's internal drawing bounds, so the
  // grid needs more than merely the slider's nominal height.
  public static final int HORIZONTAL_GRID_BOTTOM = 112;

  // Match the Apache ECharts slider default so controls remain easy to grab and read.
  private static final int VERTICAL_SLIDER_WIDTH = 30;
  private static final int HORIZONTAL_SLIDER_HEIGHT = 30;
  // ECharts creates a vertical slider by rotating its horizontal control. The rotated end
  // handle extends 11.5px beyond the nominal 30px rail; a 10px gap therefore still crosses
  // the SVG boundary. Keep the complete handle, border, and shadow inside the chart canvas.
  private static final int VERTICAL_SLIDER_RIGHT = 16;
  private static final int HORIZONTAL_SLIDER_BOTTOM = 28;

  private AnalyticsDataZoomOptions() {}

  public static List<Map<String, Object>> vertical(int rowCount, int requestedEndValue) {
    return List.of(slider("yAxisIndex", rowCount, requestedEndValue, true));
  }

  public static List<Map<String, Object>> horizontal(int rowCount, int requestedEndValue) {
    return List.of(slider("xAxisIndex", rowCount, requestedEndValue, false));
  }

  private static Map<String, Object> slider(
      String axisIndexKey, int rowCount, int requestedEndValue, boolean vertical) {
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("type", "slider");
    option.put(axisIndexKey, 0);
    option.put("startValue", 0);
    option.put("endValue", endValue(rowCount, requestedEndValue));
    // A single slider is intentional: old-platform charts use this as the sole range owner.
    // Keeping one owner prevents linked inside/slider controls from competing on refresh.
    option.put("filterMode", "filter");
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
    // ECharts' native 100% handle fills the rail without overflowing its SVG bounds.
    option.put("handleSize", "100%");
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
