package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.service.PageRecordSnapshotService;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QualityBoardOtherAnalyticsProviderTest {
  @Test
  void chartDetailAndExportShareTheSameAggregatedRows() {
    QualityBoardOtherQueryService queryService = mock(QualityBoardOtherQueryService.class);
    QualityBoardOtherWorkbookService workbookService = mock(QualityBoardOtherWorkbookService.class);
    List<QualityBoardOtherQueryService.Row> rows = List.of(
        new QualityBoardOtherQueryService.Row("CC2026R4", "装配", 2L, 1000L, 0.2D),
        new QualityBoardOtherQueryService.Row("CC2026R4", "工程图", 0L, 500L, 0D));
    when(queryService.load(
        eq(QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY),
        any(AnalyticsDashboardQueryContext.class))).thenReturn(rows);
    when(queryService.scope(
        any(QualityBoardOtherTopic.class),
        any(AnalyticsDashboardQueryContext.class))).thenReturn("CC2026R4");
    when(workbookService.export(
        QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY, "CC2026R4", rows))
        .thenReturn(new byte[] {1, 2, 3});
    QualityBoardOtherAnalyticsProvider provider = new QualityBoardOtherAnalyticsProvider(
        queryService, workbookService, mock(PageRecordSnapshotService.class));
    AnalyticsDashboardQueryContext context = AnalyticsDashboardQueryContext.empty();

    AnalyticsDashboardResponse.Chart chart = provider.loadDashboard(context).charts().stream()
        .filter(item -> item.key().equals(
            QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY.chartKey()))
        .findFirst()
        .orElseThrow();
    AnalyticsDashboardDetailResponse detail = provider.loadDetail(
        QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY.viewKey(), context);
    AnalyticsDashboardExport export = provider.export(
        QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY.exportKey(), context);

    List<?> series = (List<?>) chart.option().get("series");
    List<?> chartPoints = (List<?>) ((Map<?, ?>) series.getFirst()).get("data");
    List<Object> chartValues = chartPoints.stream()
        .map(point -> (Object) ((Map<?, ?>) point).get("value"))
        .toList();
    assertThat(chartValues).containsExactly(0.2D, 0D);
    assertThat(detail.records()).containsExactly(
        Map.of("scope", "CC2026R4", "name", "装配", "numerator", 2L,
            "denominator", 1000L, "value", 0.2D),
        Map.of("scope", "CC2026R4", "name", "工程图", "numerator", 0L,
            "denominator", 500L, "value", 0D));
    assertThat(export.content()).containsExactly(1, 2, 3);
    verify(workbookService).export(
        QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY, "CC2026R4", rows);
  }

  @Test
  void denseCategoryChartsKeepTheLegacyWindowAndReadableLabels() {
    QualityBoardOtherQueryService queryService = mock(QualityBoardOtherQueryService.class);
    QualityBoardOtherWorkbookService workbookService = mock(QualityBoardOtherWorkbookService.class);
    List<QualityBoardOtherQueryService.Row> rows = IntStream.range(0, 80)
        .mapToObj(index -> new QualityBoardOtherQueryService.Row(
            "CC2026R3", "很长的功能分类名称-" + index, 80L - index, 100L, 80D - index))
        .toList();
    when(queryService.load(any(QualityBoardOtherTopic.class), any(AnalyticsDashboardQueryContext.class)))
        .thenReturn(rows);
    when(queryService.scope(any(QualityBoardOtherTopic.class), any(AnalyticsDashboardQueryContext.class)))
        .thenReturn("CC2026R3");

    var provider = new QualityBoardOtherAnalyticsProvider(
        queryService, workbookService, mock(PageRecordSnapshotService.class));
    AnalyticsDashboardResponse dashboard = provider.loadDashboard(AnalyticsDashboardQueryContext.empty());
    AnalyticsDashboardResponse.Chart chart = dashboard.charts().stream()
        .filter(item -> item.key().equals(QualityBoardOtherTopic.FUNCTION_DEFECT_COUNT.chartKey()))
        .findFirst()
        .orElseThrow();

    Map<?, ?> xAxis = (Map<?, ?>) chart.option().get("xAxis");
    Map<?, ?> axisLabel = (Map<?, ?>) xAxis.get("axisLabel");
    List<?> dataZoom = (List<?>) chart.option().get("dataZoom");
    Map<?, ?> slider = (Map<?, ?>) dataZoom.getFirst();
    List<?> series = (List<?>) chart.option().get("series");
    Map<?, ?> firstSeries = (Map<?, ?>) series.getFirst();

    assertThat(axisLabel.get("interval")).isEqualTo("auto");
    assertThat(axisLabel.get("hideOverlap")).isEqualTo(true);
    assertThat(axisLabel.get("width")).isEqualTo(96);
    assertThat(axisLabel.get("overflow")).isEqualTo("truncate");
    assertThat(dataZoom).hasSize(1);
    assertThat(slider.get("type")).isEqualTo("slider");
    assertThat(slider.get("filterMode")).isEqualTo("filter");
    assertThat(slider.get("startValue")).isEqualTo(0);
    assertThat(slider.get("endValue"))
        .isEqualTo(AnalyticsDataZoomOptions.LEGACY_INITIAL_VIEWPORT_END_VALUE);
    assertThat(firstSeries.get("labelLayout"))
        .isEqualTo(Map.of("hideOverlap", true));
  }
}
