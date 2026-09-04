package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 排序白名单回退行为回归：sortField 为 null 是合法输入（导出请求可不带排序字段），
 * 不可变映射对 null 键查询会抛 NPE，必须回退到默认列而非异常。
 */
class IssueFactRecordSortSupportTest {

  @Test
  void test_null_sort_field_falls_back_to_default_column() {
    assertThat(IssueFactRecordSortSupport.sortColumn(null)).isEqualTo("updated_at_source");
  }

  @Test
  void test_known_sort_field_resolves_to_whitelist_column() {
    assertThat(IssueFactRecordSortSupport.sortColumn("severityLevel")).isEqualTo("lower(coalesce(severity_level, ''))");
  }

  @Test
  void test_unknown_sort_field_falls_back_to_default_column() {
    assertThat(IssueFactRecordSortSupport.sortColumn("evil; drop table x")).isEqualTo("updated_at_source");
  }
}
