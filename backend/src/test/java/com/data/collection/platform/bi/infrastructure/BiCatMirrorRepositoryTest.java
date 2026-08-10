package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Config;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class BiCatMirrorRepositoryTest {
  private static final String TEST_CONSTRAINT = "ck_test_cat_next_schedule_null";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private BiCatMirrorRepository repository;

  @BeforeEach
  void setUp() {
    repository = new BiCatMirrorRepository(
        jdbcTemplate, new TransactionTemplate(transactionManager));
    dropTestConstraint();
    jdbcTemplate.update(
        """
        update bi_cat_mirror_configs
           set enabled = false,
               base_url = 'http://baseline.invalid',
               auto_sync_enabled = false,
               sync_interval_minutes = 20,
               full_compensation_enabled = false,
               full_compensation_time = '02:00:00'
         where id = 1
        """);
    jdbcTemplate.update(
        "update bi_cat_sync_state set next_scheduled_at = null where id = 1");
  }

  @Test
  void test_enabled_auto_sync_persists_typed_next_schedule() {
    Instant now = Instant.parse("2026-08-10T02:30:00Z");

    repository.saveConfig(
        new Config(true, "https://172.22.10.56:88", true, 10, true, LocalTime.of(2, 0)),
        now);

    assertThat(repository.loadConfig()).isEqualTo(
        new Config(true, "https://172.22.10.56:88", true, 10, true, LocalTime.of(2, 0)));
    Timestamp nextScheduledAt = jdbcTemplate.queryForObject(
        "select next_scheduled_at from bi_cat_sync_state where id = 1", Timestamp.class);
    assertThat(nextScheduledAt).isEqualTo(Timestamp.from(now));
  }

  @Test
  void test_disabled_auto_sync_clears_next_schedule() {
    jdbcTemplate.update(
        "update bi_cat_sync_state set next_scheduled_at = current_timestamp where id = 1");

    repository.saveConfig(
        new Config(true, "https://172.22.10.56:88", false, 10, true, LocalTime.of(2, 0)),
        Instant.parse("2026-08-10T02:30:00Z"));

    Integer scheduled = jdbcTemplate.queryForObject(
        "select count(next_scheduled_at) from bi_cat_sync_state where id = 1", Integer.class);
    assertThat(scheduled).isZero();
  }

  @Test
  void test_schedule_state_failure_rolls_back_config_update() {
    jdbcTemplate.execute(
        "alter table bi_cat_sync_state add constraint " + TEST_CONSTRAINT
            + " check (next_scheduled_at is null)");
    try {
      assertThatThrownBy(() -> repository.saveConfig(
          new Config(true, "https://172.22.10.56:88", true, 10, true, LocalTime.of(2, 0)),
          Instant.parse("2026-08-10T02:30:00Z")))
          .isInstanceOf(RuntimeException.class);

      assertThat(repository.loadConfig()).isEqualTo(
          new Config(false, "http://baseline.invalid", false, 20, false, LocalTime.of(2, 0)));
    } finally {
      dropTestConstraint();
    }
  }

  private void dropTestConstraint() {
    jdbcTemplate.execute(
        "alter table bi_cat_sync_state drop constraint if exists " + TEST_CONSTRAINT);
  }
}
