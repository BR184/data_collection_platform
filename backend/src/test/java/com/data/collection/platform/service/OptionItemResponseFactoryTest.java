package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OptionItemResponseFactoryTest {

  @Test
  void shouldBuildLegacyBusinessOptionsLikeOldDropdowns() {
    assertThat(OptionItemResponseFactory.fromLegacyBusinessValues(List.of(
            "草图 & 工程图",
            "未设定模块",
            "未标注项目名",
            "未识别模块",
            "未标记模块",
            "无需标注",
            "GitLab接口报错",
            "",
            "张三")))
        .extracting(item -> item.value())
        .containsExactly("工程图", "张三", "草图");
  }
}
