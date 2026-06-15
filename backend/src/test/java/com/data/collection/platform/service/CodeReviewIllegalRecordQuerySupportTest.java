package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeReviewIllegalRecordQuerySupportTest {

  @Test
  void shouldMatchKeywordWithAbstractSearch() {
    CodeReviewIllegalRecordView row =
        new CodeReviewIllegalRecordView(
            "merge_request",
            1L,
            101,
            2001L,
            "[草图模块] 算数功能设计说明书评审",
            null,
            "王强",
            "草图项目",
            "sketch-repo",
            LocalDateTime.of(2026, 4, 20, 10, 0),
            "李四",
            "王强",
            "草图模块",
            "master",
            List.of("未标注模块名"),
            "",
            "",
            "",
            0,
            LocalDateTime.of(2026, 4, 20, 9, 30),
            "",
            0,
            "",
            "",
            0.0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0.0,
            0.0,
            0.0,
            0,
            0,
            "",
            0);

    assertThat(CodeReviewIllegalRecordQuerySupport.matchesKeyword(row, "ct")).isTrue();
    assertThat(CodeReviewIllegalRecordQuerySupport.matchesKeyword(row, "wq")).isTrue();
    assertThat(CodeReviewIllegalRecordQuerySupport.matchesKeyword(row, "wangqiang")).isTrue();
    assertThat(CodeReviewIllegalRecordQuerySupport.matchesKeyword(row, "fx")).isFalse();
  }
}
