package com.data.collection.platform.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MirrorRowChangeTest {
  @Test
  void test_nullable_source_row_is_preserved_as_detached_immutable_snapshot() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("id", 101L);
    source.put("title", null);

    MirrorRowChange change = new MirrorRowChange(Map.of(), source);
    source.put("title", "later mutation");
    source.put("unexpected", true);

    assertThat(change.after())
        .containsEntry("id", 101L)
        .containsEntry("title", null)
        .doesNotContainKey("unexpected");
    assertThatThrownBy(() -> change.after().put("title", "mutation"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
