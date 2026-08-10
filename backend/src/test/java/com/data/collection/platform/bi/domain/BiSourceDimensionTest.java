package com.data.collection.platform.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import org.junit.jupiter.api.Test;

class BiSourceDimensionTest {
  @Test
  void normalizesIdentifiedSourceValueWithoutInventingAnId() {
    BiSourceDimension dimension = BiSourceDimension.fromNullable("  草图  ", "未标注模块");

    assertThat(dimension.sourceValue()).isEqualTo("草图");
    assertThat(dimension.displayName()).isEqualTo("草图");
    assertThat(dimension.identified()).isTrue();
  }

  @Test
  void keepsUnknownMemberDistinctFromSameNamedRealSourceValue() {
    BiSourceDimension unknown = BiSourceDimension.unknown("未标注模块");
    BiSourceDimension real = BiSourceDimension.identified("未标注模块");

    assertThat(unknown).isNotEqualTo(real);
    assertThat(unknown.sourceValue()).isNull();
    assertThat(unknown.identified()).isFalse();
  }

  @Test
  void rejectsUnknownDimensionWithSourceValue() {
    assertThatThrownBy(() -> new BiSourceDimension("草图", "未标注模块", false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
