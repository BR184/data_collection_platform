package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SyncRunTableWorkerServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunTableWorkerService workerService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    SyncRunTableTaskMapper taskMapper = org.mockito.Mockito.mock(SyncRunTableTaskMapper.class);
    workerService = new SyncRunTableWorkerService(taskMapper, jdbcTemplate);
  }

  @Test
  void shouldStopBeforeClaimingNextTableWhenRunIsCancelling() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(44L)))
        .thenReturn(true);

    int processed = workerService.drainRunTasks(44L);

    assertThat(processed).isZero();
    verify(jdbcTemplate, never())
        .queryForObject(contains("update sync_run_table_tasks"), any(RowMapper.class), any(), any(), any());
    verify(jdbcTemplate).update(contains("set status = 'CANCELLED'"), eq(44L));
  }

  @Test
  void shouldTreatMissingRunAsNotCancelled() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(99L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThat(workerService.isRunCancellationRequested(99L)).isFalse();
  }
}
