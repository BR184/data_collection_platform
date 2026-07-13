package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CsvExportSupportTest {
  @Test
  void shouldEscapeCsvCells() {
    assertThat(CsvExportSupport.cell("a,b\"c")).isEqualTo("\"a,b\"\"c\"");
    assertThat(CsvExportSupport.cell(null)).isEmpty();
  }

  @Test
  void shouldFormatDateTimeForCsv() {
    assertThat(CsvExportSupport.dateTime(LocalDateTime.of(2026, 7, 13, 9, 8, 7)))
        .isEqualTo("2026-07-13 09:08:07");
  }
}
