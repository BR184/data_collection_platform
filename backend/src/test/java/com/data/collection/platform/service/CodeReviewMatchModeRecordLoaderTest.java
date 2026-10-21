package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 兼容模式代码走查排序契约。
 *
 * <p>同一合并请求号可与老平台多条走查记录对应（并列行），此时只按走查时间与合并请求号排序，
 * 并列顺序由物理行序决定，同一份数据在两次运行间可能返回不同顺序（黄金快照曾出现整行互换）。
 * 排序必须以来源关系声明的业务身份 {@code (source_instance, legacy_source_id)} 收尾。
 */
class CodeReviewMatchModeRecordLoaderTest {

  private static final String TIE_BREAK =
      ", nullif(btrim(legacy_source_id), '') desc nulls last";

  @Test
  void loadSourcesEndsWithLegacyBusinessIdentityTieBreak() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

    new CodeReviewMatchModeRecordLoader(jdbc).loadSources(java.util.Map.of());

    assertThat(jdbc.lastSql())
        .endsWith("order by merged_at_source desc nulls last" + TIE_BREAK);
  }

  @Test
  void exportSourcesTieBreakOnLegacyBusinessIdentity() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    CodeReviewMatchModeRecordLoader loader = new CodeReviewMatchModeRecordLoader(jdbc);

    loader.loadLegacyAllExportSources(request(), emptyGroup());

    assertThat(jdbc.lastSql())
        .endsWith(
            "order by merge_request_iid desc nulls last, merged_at_source desc nulls last"
                + TIE_BREAK);
  }

  @Test
  void defaultExportSourcesTieBreakOnLegacyBusinessIdentity() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    CodeReviewMatchModeRecordLoader loader = new CodeReviewMatchModeRecordLoader(jdbc);

    loader.loadDefaultIllegalExportSources(request(), emptyGroup());

    assertThat(jdbc.lastSql())
        .endsWith(", merge_request_iid desc" + TIE_BREAK);
  }

  private static StatisticFilterGroup emptyGroup() {
    return new StatisticFilterGroup("AND", List.of());
  }

  private static CodeReviewIllegalRecordQueryRequest request() {
    return new CodeReviewIllegalRecordQueryRequest(
        null, null, null, null, null, null, "merge_request", null, null, null, null, null, null,
        null, null, 1, 20, null, null, null);
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private String lastSql;

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      lastSql = sql;
      return List.of();
    }

    private String lastSql() {
      return lastSql;
    }
  }
}
