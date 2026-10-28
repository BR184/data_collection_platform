package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
