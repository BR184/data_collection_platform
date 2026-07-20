package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.statistics.CustomerIssueDefectSummaryBoardService;
import com.data.collection.platform.service.statistics.StatisticBoardRegistry;
import com.data.collection.platform.service.statistics.SystemTestHorizontalComparisonExportService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatisticBoardControllerUnitTest {
  @Test
  void routesIssueWorkbookExportThroughBoardCapability() {
    StatisticBoardRegistry registry = mock(StatisticBoardRegistry.class);
    CustomerIssueDefectSummaryBoardService service = mock(CustomerIssueDefectSummaryBoardService.class);
    when(registry.getRequired("customer-issue-defect-summary")).thenReturn(service);
    when(service.exportIssueRecordsWorkbook(Map.of())).thenReturn(new byte[] {1, 2, 3});
    when(service.exportIssueRecordsFilename(Map.of())).thenReturn("客户问题全量议题数据.xlsx");
    StatisticBoardController controller =
        new StatisticBoardController(
            registry,
            mock(RealtimeWorkspaceService.class),
            mock(SystemTestHorizontalComparisonExportService.class));

    var response = controller.exportBoardIssues("customer-issue-defect-summary", Map.of());

    assertThat(response.getBody()).containsExactly(1, 2, 3);
    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .contains("filename*=UTF-8''")
        .contains("%E5%AE%A2%E6%88%B7%E9%97%AE%E9%A2%98");
  }
}
