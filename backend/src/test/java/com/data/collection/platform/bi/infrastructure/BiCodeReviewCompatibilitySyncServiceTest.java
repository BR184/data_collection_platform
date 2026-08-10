package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.LegacyMysqlReadConfiguration;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class BiCodeReviewCompatibilitySyncServiceTest {
  @Test
  void test_disabled_global_compatibility_mode_does_not_touch_any_sync_table() {
    CodeReviewMatchModeConfigService configService = mock(CodeReviewMatchModeConfigService.class);
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(4L);

    var result =
        new BiCodeReviewCompatibilitySyncService(
                configService, switchService, jdbcTemplate, transactionTemplate)
            .syncNow();

    assertThat(result.published()).isFalse();
    verifyNoInteractions(configService, transactionTemplate);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class));
    assertThat(sql.getValue()).contains("bi_code_review_compatibility_sync_state");
  }

  @Test
  void test_missing_connection_marks_only_bi_compatibility_state_failed() {
    CodeReviewMatchModeConfigService configService = mock(CodeReviewMatchModeConfigService.class);
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(true);
    when(configService.loadLegacyMysqlReadConfiguration())
        .thenReturn(new LegacyMysqlReadConfiguration(List.of(), "", "", 1000, true));
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);

    var result =
        new BiCodeReviewCompatibilitySyncService(
                configService, switchService, jdbcTemplate, transactionTemplate)
            .syncNow();

    assertThat(result.published()).isFalse();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .update(sql.capture(), org.mockito.ArgumentMatchers.<Object[]>any());
    assertThat(sql.getValue())
        .contains("bi_code_review_compatibility_sync_state")
        .doesNotContain("code_review_match_mode_records");
    verifyNoInteractions(transactionTemplate);
  }

  @Test
  void test_legacy_sql_date_is_converted_without_unsupported_to_instant_call() {
    LocalDate sourceDate = LocalDate.of(2026, 8, 5);

    LocalDateTime parsed =
        BiCodeReviewCompatibilitySyncService.parseLegacyDateTime(Date.valueOf(sourceDate));

    assertThat(parsed).isEqualTo(sourceDate.atStartOfDay());
  }

  @Test
  void test_source_connection_failure_keeps_published_bi_snapshot_and_existing_compatibility_table() {
    CodeReviewMatchModeConfigService configService = mock(CodeReviewMatchModeConfigService.class);
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(true);
    when(configService.loadLegacyMysqlReadConfiguration())
        .thenReturn(
            new LegacyMysqlReadConfiguration(
                List.of(new LegacyMysqlReadConfiguration.Source("cc", "jdbc:unsupported:legacy")),
                "readonly",
                "secret",
                1000,
                true));
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(9L);

    var result =
        new BiCodeReviewCompatibilitySyncService(
                configService, switchService, jdbcTemplate, transactionTemplate)
            .syncNow();

    assertThat(result.published()).isFalse();
    assertThat(result.version()).isEqualTo(9L);
    verifyNoInteractions(transactionTemplate);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
        .update(sql.capture(), org.mockito.ArgumentMatchers.<Object[]>any());
    assertThat(sql.getAllValues())
        .allSatisfy(
            statement ->
                assertThat(statement)
                    .doesNotContain(
                        "delete from bi_code_review_compatibility_records target",
                        "insert into bi_code_review_compatibility_records(")
                    .doesNotContain("code_review_match_mode_records"));
  }
}
