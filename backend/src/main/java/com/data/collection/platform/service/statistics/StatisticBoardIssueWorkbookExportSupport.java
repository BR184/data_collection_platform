package com.data.collection.platform.service.statistics;

import java.util.Map;

/** Capability implemented by statistic boards that own an issue-detail workbook contract. */
public interface StatisticBoardIssueWorkbookExportSupport {
  byte[] exportIssueRecordsWorkbook(Map<String, String> filters);

  String exportIssueRecordsFilename(Map<String, String> filters);
}
