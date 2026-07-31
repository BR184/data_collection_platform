package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CodeReviewMetricEnrichmentRepositoryTest {

  @Test
  void completedSyncRunPersistsBoundedKeysetCursorWithoutClearingValidMetrics() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    jdbc.nextRunIds = List.of(42L);
    jdbc.candidateRows = List.of(candidateRow(99L));
    CodeReviewMetricEnrichmentRepository repository = repository(jdbc);

    int enqueued = repository.enqueueNextCompletedRun("default", 25);

    assertThat(enqueued).isOne();
    assertThat(jdbc.candidateQueryArgs).containsExactly(42L, 42L, 0L, 25);
    assertThat(jdbc.updates).anySatisfy(update -> {
      assertThat(update.sql()).contains("active_run_cursor_merge_request_id = ?");
      assertThat(update.args()).containsExactly(99L, "default", 42L);
    });
    SqlCall upsert = jdbc.updates.stream()
        .filter(update -> update.sql().contains(
            "on conflict (source_instance, project_id, merge_request_iid)"))
        .findFirst()
        .orElseThrow();
    String updateClause = upsert.sql().substring(upsert.sql().indexOf("do update set"));
    assertThat(updateClause)
        .doesNotContain("added_lines =")
        .doesNotContain("deleted_lines =");
  }

  @Test
  void emptyActiveRunPageAdvancesCompletedRunAndClearsCursor() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    jdbc.activeRunId = 42L;
    jdbc.activeRunCursor = 99L;
    CodeReviewMetricEnrichmentRepository repository = repository(jdbc);

    int enqueued = repository.enqueueNextCompletedRun("default", 25);

    assertThat(enqueued).isZero();
    assertThat(jdbc.updates).anySatisfy(update -> {
      assertThat(update.sql())
          .contains("last_processed_sync_run_id = ?")
          .contains("active_sync_run_id = null")
          .contains("active_run_cursor_merge_request_id = 0");
      assertThat(update.args()).containsExactly(42L, "default", 42L);
    });
  }

  @Test
  void claimReclaimsStaleRunningRowsWithoutAbandoningRetryableWork() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    CodeReviewMetricEnrichmentRepository repository = repository(jdbc);

    repository.claim("formal", 20);

    assertThat(jdbc.events).hasSize(1);
    assertThat(jdbc.events.getFirst())
        .contains("update code_review_external_metrics")
        .contains("enrichment_status = 'RUNNING'")
        .contains("enrichment_status = 'RETRY'")
        .contains("enrichment_status = 'RUNNING'")
        .contains("source_instance = ?")
        .doesNotContain("enrichment_attempts < ?");
    assertThat(jdbc.queryArgs).containsExactly("formal", 20);
  }

  @Test
  void retryableFailureUsesCappedBackoffWithoutAttemptLimit() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    CodeReviewMetricEnrichmentRepository repository = repository(jdbc);

    repository.markFailed(8L, true, "temporary");

    assertThat(jdbc.updates).singleElement().satisfies(update -> {
      assertThat(update.sql())
          .contains("when ? then 'RETRY'")
          .contains("least(greatest(enrichment_attempts - 1, 0), 20)")
          .doesNotContain("enrichment_attempts < ?");
      assertThat(update.args()).containsExactly(true, "temporary", true, 30, 8L);
    });
  }

  private CodeReviewMetricEnrichmentRepository repository(JdbcTemplate jdbcTemplate) {
    return new CodeReviewMetricEnrichmentRepository(jdbcTemplate, new GitlabMirrorProperties());
  }

  private ResultSet candidateRow(long mergeRequestId) {
    ResultSet resultSet = mock(ResultSet.class);
    try {
      when(resultSet.getLong("project_id")).thenReturn(9L);
      when(resultSet.getLong("merge_request_id")).thenReturn(mergeRequestId);
      when(resultSet.getLong("merge_request_iid")).thenReturn(7L);
      when(resultSet.getString("project_path")).thenReturn("group/project");
      when(resultSet.getString("title")).thenReturn("[装配] 修复问题");
      when(resultSet.getTimestamp("source_updated_at"))
          .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 7, 30, 10, 0)));
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
    return resultSet;
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private long lastProcessedRunId;
    private Long activeRunId;
    private long activeRunCursor;
    private List<Long> nextRunIds = List.of();
    private List<ResultSet> candidateRows = List.of();
    private List<Object> candidateQueryArgs = List.of();
    private List<Object> queryArgs = List.of();
    private final List<SqlCall> updates = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
      updates.add(new SqlCall(sql, List.copyOf(Arrays.asList(args))));
      events.add(sql);
      return 1;
    }

    @Override
    public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
      ResultSet resultSet = mock(ResultSet.class);
      try {
        when(resultSet.getLong("last_processed_sync_run_id")).thenReturn(lastProcessedRunId);
        when(resultSet.getObject("active_sync_run_id", Long.class)).thenReturn(activeRunId);
        when(resultSet.getLong("active_run_cursor_merge_request_id")).thenReturn(activeRunCursor);
        return rowMapper.mapRow(resultSet, 0);
      } catch (SQLException error) {
        throw new IllegalStateException(error);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      return (List<T>) nextRunIds;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      events.add(sql);
      if (!sql.contains("with recursive namespace_paths")) {
        queryArgs = List.copyOf(Arrays.asList(args));
        return List.of();
      }
      candidateQueryArgs = List.copyOf(Arrays.asList(args));
      List<T> rows = new ArrayList<>();
      for (int index = 0; index < candidateRows.size(); index++) {
        try {
          rows.add(rowMapper.mapRow(candidateRows.get(index), index));
        } catch (SQLException error) {
          throw new IllegalStateException(error);
        }
      }
      return rows;
    }
  }

  private record SqlCall(String sql, List<Object> args) {}
}
