package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunTableStateDiagnostics;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SyncRunTableDiagnosticsServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunTableDiagnosticsService diagnosticsService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    diagnosticsService = new SyncRunTableDiagnosticsService(jdbcTemplate);
  }

  @Test
  void shouldReturnDirtySemanticsAndBlockingRunForTables() throws Exception {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    ResultSet row = dirtyTableRow();
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq("alpha")))
        .thenAnswer(invocation -> {
          RowMapper<SyncRunTableStateDiagnostics> mapper = invocation.getArgument(1);
          return List.of(mapper.mapRow(row, 0));
        });
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1L), eq("alpha")))
        .thenReturn(List.of("RUNNING"));
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L), eq("alpha")))
        .thenReturn(1, 1);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L), eq("alpha"), anyString()))
        .thenReturn(0, 1, 0, 0, 0);

    Map<String, Object> response = diagnosticsService.tableDiagnostics(config);

    assertThat(response)
        .containsEntry("configId", 1L)
        .containsEntry("sourceInstance", "alpha")
        .containsEntry("status", "RUNNING")
        .containsEntry("tableCount", 1)
        .containsEntry("dirtyTableCount", 1)
        .containsEntry("runningTaskCount", 1);
    @SuppressWarnings("unchecked")
    List<SyncRunTableStateDiagnostics> tables =
        (List<SyncRunTableStateDiagnostics>) response.get("tables");
    assertThat(tables).hasSize(1);
    SyncRunTableStateDiagnostics table = tables.getFirst();
    assertThat(table.sourceTable()).isEqualTo("issues");
    assertThat(table.dirty()).isTrue();
    assertThat(table.dirtyReason()).isEqualTo("row_count_drift");
    assertThat(table.blockingRunId()).isEqualTo("sr_full_alpha");
    assertThat(table.lastVerifiedAt()).isEqualTo(LocalDateTime.of(2026, 5, 15, 9, 0));
    assertThat(table.lastAppliedAt()).isEqualTo(LocalDateTime.of(2026, 5, 15, 9, 5));
    assertThat(table.sourceRows()).isEqualTo(120L);
    assertThat(table.mirrorRows()).isEqualTo(100L);
    assertThat(table.driftSummary()).isEqualTo("source=120, mirror=100, delta=20");
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(1L), eq("alpha"));
    assertThat(sql.getValue())
        .contains("run.run_id as external_run_id")
        .contains("blocking.external_run_id as blocking_run_id")
        .doesNotContain("blocking.run_id as blocking_run_id");
  }

  private ResultSet dirtyTableRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("source_table")).thenReturn("issues");
    when(rs.getString("mirror_table")).thenReturn("ods_gitlab_issues");
    when(rs.getString("primary_key_columns")).thenReturn("id");
    when(rs.getString("updated_at_column")).thenReturn("updated_at");
    when(rs.getString("row_strategy")).thenReturn("INCREMENTAL");
    when(rs.getBoolean("sync_enabled")).thenReturn(true);
    when(rs.getBoolean("dirty_flag")).thenReturn(true);
    when(rs.getString("dirty_reason")).thenReturn("row_count_drift");
    when(rs.getString("blocking_run_id")).thenReturn("sr_full_alpha");
    when(rs.getTimestamp("last_full_verified_at")).thenReturn(timestamp(2026, 5, 15, 9, 0));
    when(rs.getTimestamp("last_success_at")).thenReturn(timestamp(2026, 5, 15, 9, 5));
    when(rs.getTimestamp("last_watermark_at")).thenReturn(timestamp(2026, 5, 15, 9, 4));
    when(rs.getString("last_cursor_pk")).thenReturn("100");
    when(rs.getObject("source_row_count")).thenReturn(120L);
    when(rs.getObject("mirror_row_count")).thenReturn(100L);
    when(rs.getString("schema_fingerprint")).thenReturn("abc");
    when(rs.getString("last_error")).thenReturn(null);
    when(rs.getObject("retry_count")).thenReturn(1);
    when(rs.getString("latest_task_type")).thenReturn("FULL_REPAIR");
    when(rs.getString("latest_task_status")).thenReturn("RUNNING");
    when(rs.getTimestamp("latest_task_run_after")).thenReturn(timestamp(2026, 5, 15, 9, 6));
    when(rs.getTimestamp("latest_task_heartbeat_at")).thenReturn(timestamp(2026, 5, 15, 9, 7));
    when(rs.getTimestamp("latest_task_lease_until")).thenReturn(timestamp(2026, 5, 15, 9, 8));
    when(rs.getObject("latest_task_rows_scanned")).thenReturn(30L);
    when(rs.getObject("latest_task_rows_applied")).thenReturn(20L);
    when(rs.getString("latest_task_error")).thenReturn(null);
    return rs;
  }

  private Timestamp timestamp(int year, int month, int day, int hour, int minute) {
    return Timestamp.valueOf(LocalDateTime.of(year, month, day, hour, minute));
  }
}
