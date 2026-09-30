package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

import com.data.collection.platform.entity.MirrorRowChange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 维表字段级血缘的行为锁定：非事实列变化不得反向展开为事实根。
 *
 * <p>断言依据是解析过程中是否触达数据库：被过滤掉的变化在解析任何根之前就返回，因此对
 * {@link JdbcTemplate} 零交互；反之相关列变化必然发起反查查询。
 */
class GitlabFactChangeResolverRelevantColumnsTest {

  @Test
  void non_fact_column_change_on_dimension_table_does_not_expand_roots() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabFactChangeResolver resolver = new GitlabFactChangeResolver(jdbcTemplate);

    List<com.data.collection.platform.entity.FactChangeIdentity> identities =
        resolver.resolve(
            "alpha",
            "projects",
            List.of(
                new MirrorRowChange(
                    Map.of("id", 900L, "name", "same", "description", "before", "last_activity_at", "t1"),
                    Map.of("id", 900L, "name", "same", "description", "after", "last_activity_at", "t2"))));

    assertThat(identities).isEmpty();
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void fact_relevant_column_change_on_dimension_table_still_expands_roots() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabFactChangeResolver resolver = new GitlabFactChangeResolver(jdbcTemplate);

    resolver.resolve(
        "alpha",
        "projects",
        List.of(
            new MirrorRowChange(
                Map.of("id", 900L, "name", "before"), Map.of("id", 900L, "name", "after"))));

    assertThat(mockingDetails(jdbcTemplate).getInvocations()).isNotEmpty();
  }

  @Test
  void dimension_row_deletion_still_expands_roots() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabFactChangeResolver resolver = new GitlabFactChangeResolver(jdbcTemplate);

    resolver.resolve(
        "alpha", "labels", List.of(new MirrorRowChange(Map.of("id", 44L, "title", "x"), Map.of())));

    assertThat(mockingDetails(jdbcTemplate).getInvocations()).isNotEmpty();
  }
}
