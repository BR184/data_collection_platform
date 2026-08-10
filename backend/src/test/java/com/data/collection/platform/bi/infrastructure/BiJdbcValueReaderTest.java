package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BiJdbcValueReaderTest {
  @ParameterizedTest
  @MethodSource("integralJdbcNumbers")
  void readsIntegralJdbcNumbersWithoutDriverSpecificTargetConversion(
      Number databaseValue,
      long expected) throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getObject("metric_value")).thenReturn(databaseValue);

    assertThat(BiJdbcValueReader.nullableLong(resultSet, "metric_value"))
        .isEqualTo(expected);
  }

  @Test
  void preservesDatabaseNullInsteadOfSubstitutingZero() throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getObject("metric_value")).thenReturn(null);

    assertThat(BiJdbcValueReader.nullableLong(resultSet, "metric_value")).isNull();
  }

  @Test
  void rejectsFractionalValuesInsteadOfSilentlyTruncating() throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getObject("metric_value")).thenReturn(new BigDecimal("12.5"));

    assertThatThrownBy(() -> BiJdbcValueReader.nullableLong(resultSet, "metric_value"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("metric_value");
  }

  @Test
  void rejectsNonNumericValuesWhenSchemaAndMapperDiverge() throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getObject("metric_value")).thenReturn("12");

    assertThatThrownBy(() -> BiJdbcValueReader.nullableLong(resultSet, "metric_value"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("metric_value");
  }

  private static Stream<Arguments> integralJdbcNumbers() {
    return Stream.of(
        Arguments.of((byte) 7, 7L),
        Arguments.of((short) 8, 8L),
        Arguments.of(9, 9L),
        Arguments.of(10L, 10L),
        Arguments.of(new BigInteger("11"), 11L),
        Arguments.of(new BigDecimal("12"), 12L));
  }
}
