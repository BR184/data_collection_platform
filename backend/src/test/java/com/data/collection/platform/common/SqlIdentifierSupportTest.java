package com.data.collection.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SqlIdentifierSupportTest {
  @Test
  void shouldQuoteIdentifierAndEscapeEmbeddedQuotes() {
    assertThat(SqlIdentifierSupport.quoteIdentifier("issue\"events"))
        .isEqualTo("\"issue\"\"events\"");
  }

  @Test
  void shouldRejectBlankIdentifier() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SqlIdentifierSupport.quoteIdentifier("  "))
        .withMessage("SQL 标识符不能为空");
  }
}
