package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IssueLabelRulesTest {

  @Test
  void parseLegacyLabelMapAlignsWithOldPlatformPrefixedLabels() {
    // 老平台兼容模块/工具箱标签中的中文冒号、ASCII 冒号和连字符分隔符。
    Map<String, List<String>> result = IssueLabelRules.parseLegacyLabelMap(List.of(
        "模块：草图",
        "工具箱:工具",
        "模块-工程图",
        "严重程度：致命"));

    assertThat(result.get("模块")).containsExactlyInAnyOrder("草图", "工具", "工程图");
    assertThat(result.get("严重程度")).containsExactly("致命");
    assertThat(result).hasSize(2);
  }

  @Test
  void parseLegacyLabelMapAlignsWithOldPlatformKeywordRecognition() {
    // 老平台输入：["系统测试-第一轮", "回归测试"]
    // 老平台输出：{"测试阶段": ["系统测试-第一轮", "回归测试"]}
    Map<String, List<String>> result = IssueLabelRules.parseLegacyLabelMap(List.of(
        "系统测试-第一轮",
        "回归测试"));

    assertThat(result.get("测试阶段")).containsExactlyInAnyOrder("系统测试-第一轮", "回归测试");
    assertThat(result).hasSize(1);
  }

  @Test
  void parseLegacyLabelMapAlignsWithOldPlatformEnumMatching() {
    // 老平台输入：["P1", "技术卡点"]
    // 老平台输出：{"紧急程度": ["P1"], "延期原因": ["技术卡点"]}
    Map<String, List<String>> result = IssueLabelRules.parseLegacyLabelMap(List.of(
        "P1",
        "技术卡点"));

    assertThat(result.get("紧急程度")).containsExactly("P1");
    assertThat(result.get("延期原因")).containsExactly("技术卡点");
    assertThat(result).hasSize(2);
  }

  @Test
  void parseLegacyLabelMapAlignsWithOldPlatformUnrecognizedDropped() {
    // 老平台输入：["模块：草图", "随便写的标签"]
    // 老平台输出：{"模块": ["草图"]}，"随便写的标签" 被丢弃
    Map<String, List<String>> result = IssueLabelRules.parseLegacyLabelMap(List.of(
        "模块：草图",
        "随便写的标签"));

    assertThat(result.get("模块")).containsExactly("草图");
    assertThat(result).hasSize(1);
  }
}
