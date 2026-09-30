package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 代码走查违规记录排序契约。
 *
 * <p>排序键以唯一主键收尾，否则并列行（同一 {@code merged_at_source} 与 {@code merge_request_iid}
 * 在不同项目下重复出现）的顺序由物理行序决定，同一份代码在两次运行间可能给出不同顺序：
 * 导出快照曾出现整行互换，分页列表也会出现跨页重复或漏行。
 */
class CodeReviewIllegalRecordSqlQueryBuilderTest {

  @Test
  void orderByClauseEndsWithUniquePrimaryKeyTieBreak() {
    assertThat(CodeReviewIllegalRecordSqlQueryBuilder.orderByClause(null, null))
        .startsWith(" order by merged_at_source desc nulls first, merged_at_source desc nulls first, merge_request_iid desc")
        .endsWith(", id desc");
  }

  @Test
  void orderByClauseTieBreaksInTheRequestedDirection() {
    assertThat(CodeReviewIllegalRecordSqlQueryBuilder.orderByClause(null, "asc"))
        .endsWith(", id asc");
  }
}
