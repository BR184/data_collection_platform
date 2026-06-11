package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class),
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
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class),
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
}
