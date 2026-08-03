package com.data.collection.platform.service;

import java.util.List;
import java.util.Locale;

/** SQL 侧计划合并版本分支成员谓词。 */
final class CustomerIssuePlannedMergeBranchSqlSupport {
  private CustomerIssuePlannedMergeBranchSqlSupport() {}

  static SqlPredicate matches(String column, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    if (expected == null) {
      return new SqlPredicate("", List.of());
    }
    return new SqlPredicate(
        """
        exists (
          select 1
            from regexp_split_to_table(coalesce(%s, ''), '&') as planned_merge_member(value)
           where nullif(btrim(planned_merge_member.value), '') is not null
             and lower(btrim(planned_merge_member.value)) = ?)
        """.formatted(column),
        List.of(expected.toLowerCase(Locale.ROOT)));
  }
}
