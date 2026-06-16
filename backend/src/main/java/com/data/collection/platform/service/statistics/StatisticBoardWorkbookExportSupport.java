package com.data.collection.platform.service.statistics;

import java.util.Map;

public interface StatisticBoardWorkbookExportSupport {
  byte[] exportBoardWorkbook(Map<String, String> filters);

  String exportFilename();
}
