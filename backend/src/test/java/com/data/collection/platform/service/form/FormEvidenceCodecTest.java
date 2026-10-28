package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 登记证据编码契约：NULL 与空串、数字与同形文本、含分隔字符的文本都必须可区分，
 * 且相同输入逐字节稳定。
 */
class FormEvidenceCodecTest {

  private final FormEvidenceCodec codec = new FormEvidenceCodec(new ObjectMapper());

  @Test
  void shouldDistinguishNullFromEmptyStringAndSeparatorText() {
    String nullValue = fingerprintOfSingle("note", null);
    String emptyValue = fingerprintOfSingle("note", "");
    String separatorValue = fingerprintOfSingle("note", "a\u0001b");
    String plainValue = fingerprintOfSingle("note", "a");

    assertThat(nullValue).isNotEqualTo(emptyValue);
    assertThat(plainValue).isNotEqualTo(separatorValue);
    assertThat(emptyValue).isNotEqualTo(plainValue);
  }

  @Test
  void shouldNotCollideWhenSeparatorShiftsAcrossAdjacentTextFields() {
    String shiftedLeft = fingerprintOfPair("a\u0001b", "c");
    String shiftedRight = fingerprintOfPair("a", "b\u0001c");
    assertThat(shiftedLeft).isNotEqualTo(shiftedRight);
  }

  @Test
  void shouldDistinguishNumericTextFromNumber() {
    ObjectNode numberRow = codec.row();
    codec.putNumber(numberRow, "value", 30L);
    ObjectNode textRow = codec.row();
    codec.putText(textRow, "value", "30");
    assertThat(codec.fingerprint(numberRow)).isNotEqualTo(codec.fingerprint(textRow));
  }

  @Test
  void shouldBeDeterministicForSameInput() {
    assertThat(fingerprintOfSingle("note", "同一值")).isEqualTo(fingerprintOfSingle("note", "同一值"));
  }

  @Test
  void shouldKeepArrayElementBoundariesAcrossJdbcAndCanonicalValues() {
    String single = fingerprintOfArray(jdbcArray("a, b"));
    String twoElements = fingerprintOfArray(jdbcArray("a", "b"));
    assertThat(single).isNotEqualTo(twoElements);
    // JDBC 原值与已规范值必须走同一编码，归一化不得改变数组类型。
    assertThat(fingerprintOfArray(List.of("a, b"))).isEqualTo(single);
    assertThat(fingerprintOfArray(List.of("a", "b"))).isEqualTo(twoElements);
  }

  @Test
  void shouldNormalizeRepeatedlyWithoutValueLoss() {
    Object once = FormEvidenceCodec.canonical(jdbcArray("a, b", "b"));
    Object twice = FormEvidenceCodec.canonical(once);
    Object thrice = FormEvidenceCodec.canonical(twice);
    assertThat(once).isInstanceOf(List.class);
    assertThat(twice).isEqualTo(once);
    assertThat(thrice).isEqualTo(once);
    assertThat(fingerprintOfArray(once)).isEqualTo(fingerprintOfArray(thrice));
  }

  @Test
  void shouldDistinguishArrayBoundariesWithSeparatorsQuotesAndNulls() {
    String oneElement = fingerprintOfArray(jdbcArray("a]b"));
    String twoElements = fingerprintOfArray(jdbcArray("a]", "b"));
    String empty = fingerprintOfArray(jdbcArray());
    String nullElement = fingerprintOfArray(jdbcArray((Object) null));
    String emptyText = fingerprintOfArray(jdbcArray(""));
    String literalNull = fingerprintOfArray(jdbcArray("null"));
    String quoteElement = fingerprintOfArray(jdbcArray("\"a, b\""));
    assertThat(oneElement).isNotEqualTo(twoElements);
    assertThat(empty).isNotEqualTo(nullElement);
    assertThat(nullElement).isNotEqualTo(emptyText);
    assertThat(emptyText).isNotEqualTo(literalNull);
    assertThat(quoteElement).isNotEqualTo(fingerprintOfArray(jdbcArray("a, b")));
  }

  private String fingerprintOfArray(Object value) {
    ObjectNode row = codec.row();
    codec.putValue(row, "tags", value);
    return codec.fingerprint(row);
  }

  private static Array jdbcArray(Object... items) {
    return new Array() {
      @Override
      public String getBaseTypeName() {
        return "text";
      }

      @Override
      public int getBaseType() {
        return Types.ARRAY;
      }

      @Override
      public Object getArray() {
        return items;
      }

      @Override
      public Object getArray(Map<String, Class<?>> map) {
        return items;
      }

      @Override
      public Object getArray(long index, int count) {
        return items;
      }

      @Override
      public Object getArray(long index, int count, Map<String, Class<?>> map) {
        return items;
      }

      @Override
      public ResultSet getResultSet() {
        throw new UnsupportedOperationException();
      }

      @Override
      public ResultSet getResultSet(Map<String, Class<?>> map) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ResultSet getResultSet(long index, int count) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void free() {}
    };
  }

  private String fingerprintOfSingle(String key, String value) {
    ObjectNode row = codec.row();
    codec.putText(row, key, value);
    return codec.fingerprint(row);
  }

  private String fingerprintOfPair(String reviewer, String note) {
    ObjectNode row = codec.row();
    codec.putText(row, "reviewer", reviewer);
    codec.putText(row, "note", note);
    return codec.fingerprint(row);
  }
}
