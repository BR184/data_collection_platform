package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcLabelGroupRepositoryTest {
  @Mock private NamedParameterJdbcTemplate jdbcTemplate;

  @Test
  void shouldCheckDuplicateNameWithoutNullableExcludeIdWhenCreating() {
    JdbcLabelGroupRepository repository = new JdbcLabelGroupRepository(jdbcTemplate);
    when(jdbcTemplate.queryForObject(
            anyString(),
            any(MapSqlParameterSource.class),
            eq(Integer.class)))
        .thenReturn(0);

    boolean exists = repository.existsByName("核心人员", null);

    assertThat(exists).isFalse();
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), eq(Integer.class));
    assertThat(sqlCaptor.getValue()).doesNotContain("excludeId");
    assertThat(paramsCaptor.getValue().hasValue("excludeId")).isFalse();
  }

  @Test
  void shouldCheckDuplicateNameWithExcludeIdWhenUpdating() {
    JdbcLabelGroupRepository repository = new JdbcLabelGroupRepository(jdbcTemplate);
    when(jdbcTemplate.queryForObject(
            anyString(),
            any(MapSqlParameterSource.class),
            eq(Integer.class)))
        .thenReturn(1);

    boolean exists = repository.existsByName("核心人员", 10L);

    assertThat(exists).isTrue();
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), eq(Integer.class));
    assertThat(sqlCaptor.getValue()).contains("id <> :excludeId");
    assertThat(paramsCaptor.getValue().getValue("excludeId")).isEqualTo(10L);
  }

  @Test
  void shouldPersistDynamicRuleComputationStatus() {
    JdbcLabelGroupRepository repository = new JdbcLabelGroupRepository(jdbcTemplate);
    OffsetDateTime computedAt = OffsetDateTime.parse("2026-06-11T18:00:00+08:00");

    repository.replaceDynamicRule(
        3L,
        new LabelGroupDynamicRuleRecord(
            null,
            3L,
            "{\"days\":30}",
            "STRING",
            "SUCCESS",
            null,
            computedAt));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbcTemplate, times(2))
        .update(sqlCaptor.capture(), paramsCaptor.capture());
    String insertSql = sqlCaptor.getAllValues().getLast();
    MapSqlParameterSource insertParams = paramsCaptor.getAllValues().getLast();
    assertThat(insertSql).contains("last_status", "last_error", "last_computed_at");
    assertThat(insertParams.getValue("lastStatus")).isEqualTo("SUCCESS");
    assertThat(insertParams.getValue("lastError")).isNull();
    assertThat(insertParams.getValue("lastComputedAt")).isInstanceOf(Timestamp.class);
  }
}
