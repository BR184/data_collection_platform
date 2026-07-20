package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsDataZoomOptionsTest {
  @Test
  void verticalSliderKeepsTheRotatedHandleInsideTheRightCanvasEdge() {
    List<Map<String, Object>> options = AnalyticsDataZoomOptions.vertical(
        15, AnalyticsDataZoomOptions.LEGACY_INITIAL_VIEWPORT_END_VALUE);

    assertThat(options).hasSize(1);
    assertThat(options.getFirst())
        .containsEntry("type", "slider")
        .containsEntry("width", 30)
        .containsEntry("right", 16)
        .containsEntry("handleSize", "100%")
        .containsEntry("filterMode", "filter")
        .containsEntry("startValue", 0)
        .containsEntry("endValue", 10);
  }

  @Test
  void horizontalSliderKeepsTheLegacyViewportAndVisibleBottomClearance() {
    List<Map<String, Object>> options = AnalyticsDataZoomOptions.horizontal(
        15, AnalyticsDataZoomOptions.LEGACY_INITIAL_VIEWPORT_END_VALUE);

    assertThat(options)
        .hasSize(1);
    assertThat(options.getFirst())
        .containsEntry("type", "slider")
        .containsEntry("height", 30)
        .containsEntry("bottom", 28)
        .containsEntry("handleSize", "100%")
        .containsEntry("filterMode", "filter")
        .containsEntry("startValue", 0)
        .containsEntry("endValue", 10);
    assertThat(AnalyticsDataZoomOptions.HORIZONTAL_GRID_BOTTOM).isEqualTo(112);
  }
}
