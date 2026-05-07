package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import org.junit.jupiter.api.Test;

class CsvExportSupportTest {
  @Test
  void shouldAllowExportWithinRowLimit() {
    assertThatCode(() -> CsvExportSupport.ensureWithinRowLimit(CsvExportSupport.MAX_EXPORT_ROWS))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldRejectExportBeyondRowLimit() {
    assertThatThrownBy(() -> CsvExportSupport.ensureWithinRowLimit(CsvExportSupport.MAX_EXPORT_ROWS + 1L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("请缩小筛选条件");
  }
}
