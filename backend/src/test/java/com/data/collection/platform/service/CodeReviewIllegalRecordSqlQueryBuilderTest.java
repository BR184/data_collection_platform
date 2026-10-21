package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 代码走查违规记录排序契约。
 *
 * <p>排序键以稳定业务身份收尾，否则并列行（同一 {@code merged_at_source} 与 {@code merge_request_iid}
 * 在不同项目下重复出现）的顺序由物理行序决定，同一份代码在两次运行间可能给出不同顺序：
 * 导出快照曾出现整行互换，分页列表也会出现跨页重复或漏行。
 *
 * <p>物理修订 {@code id} 会随每次全量重建重新分配，不能作为业务顺序；业务根首次出现顺序由
 * {@code business_order_id} 给出（同根多修订共享），同根再按可见区间起点区分修订先后。
 */
class CodeReviewIllegalRecordSqlQueryBuilderTest {

  @Test
  void orderByClauseEndsWithStableBusinessIdentityTieBreak() {
    assertThat(CodeReviewIllegalRecordSqlQueryBuilder.orderByClause(null, null))
        .startsWith(" order by merged_at_source desc nulls first, merged_at_source desc nulls first, merge_request_iid desc")
        .endsWith(", business_order_id desc, visible_from desc nulls last");
  }

  @Test
  void orderByClauseNeverFallsBackToPhysicalRevisionId() {
    assertThat(CodeReviewIllegalRecordSqlQueryBuilder.orderByClause(null, "asc"))
        .doesNotContain(", id ")
        .endsWith(", business_order_id desc, visible_from desc nulls last");
  }
}
