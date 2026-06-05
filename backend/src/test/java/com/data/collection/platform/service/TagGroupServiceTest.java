package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagGroupServiceTest {

  @Test
  void shouldLoadEnabledGroupsValuesAndPreferSourceSpecificMappings() throws Exception {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.query(ArgumentMatchers.contains("from tag_group"), ArgumentMatchers.any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(1);
              return List.of(
                  mapGroup(mapper, 1L, "issue", "module", "模块", "multiple", 10, "split_exact_comma"));
            });
    when(jdbcTemplate.query(ArgumentMatchers.contains("from tag_value"), ArgumentMatchers.any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(1);
              return List.of(
                  mapValue(mapper, 11L, 1L, "sketch", "草图", "standard", 1, false));
            });
    when(jdbcTemplate.query(ArgumentMatchers.contains("from tag_value_mapping"), ArgumentMatchers.any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(1);
              return List.of(
                  mapMapping(mapper, 101L, 11L, "global-sketch", null, "placeholder_value"),
                  mapMapping(mapper, 102L, 11L, "cc-sketch", "cc", null));
            });

    TagGroupService service = new TagGroupService(jdbcTemplate);

    var response = service.getTagGroups("issue");
    var mappings = service.resolveMappings("issue", "module", "sketch", "cc");

    assertThat(response.domain()).isEqualTo("issue");
    assertThat(response.schemaHash()).isNotBlank();
    assertThat(response.groups()).hasSize(1);
    assertThat(response.groups().getFirst().matchStrategyName()).isEqualTo("split_exact_comma");
    assertThat(response.groups().getFirst().values().getFirst().label()).isEqualTo("草图");
    assertThat(response.groups().getFirst().values().getFirst().unmappedReason())
        .isEqualTo("placeholder_value");
    assertThat(mappings).containsExactly("cc-sketch");
  }

  @Test
  void shouldCacheLoadedMappingsUntilReload() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.query(ArgumentMatchers.anyString(), ArgumentMatchers.any(RowMapper.class)))
        .thenReturn(List.of());
    TagGroupService service = new TagGroupService(jdbcTemplate);

    service.getTagGroups("issue");
    service.getTagGroups("issue");
    service.reload();
    service.getTagGroups("issue");

    verify(jdbcTemplate, times(6)).query(ArgumentMatchers.anyString(), ArgumentMatchers.any(RowMapper.class));
  }

  @Test
  void shouldLoadAtStartupAndRefreshScheduledCacheEveryTime() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.query(ArgumentMatchers.anyString(), ArgumentMatchers.any(RowMapper.class)))
        .thenReturn(List.of());
    TagGroupService service = new TagGroupService(jdbcTemplate);

    service.loadAtStartup();
    service.refreshExpiredCache();

    verify(jdbcTemplate, times(6)).query(ArgumentMatchers.anyString(), ArgumentMatchers.any(RowMapper.class));
  }

  private Object mapGroup(
      RowMapper<?> mapper,
      Long id,
      String domain,
      String groupKey,
      String label,
      String selectionMode,
      int sortOrder,
      String matchStrategyName)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(id);
    when(rs.getString("domain")).thenReturn(domain);
    when(rs.getString("group_key")).thenReturn(groupKey);
    when(rs.getString("label")).thenReturn(label);
    when(rs.getString("selection_mode")).thenReturn(selectionMode);
    when(rs.getInt("sort_order")).thenReturn(sortOrder);
    when(rs.getString("match_strategy_name")).thenReturn(matchStrategyName);
    return mapper.mapRow(rs, 0);
  }

  private Object mapValue(
      RowMapper<?> mapper,
      Long id,
      Long groupId,
      String valueKey,
      String label,
      String valueType,
      int sortOrder,
      boolean disabled)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(id);
    when(rs.getLong("group_id")).thenReturn(groupId);
    when(rs.getString("value_key")).thenReturn(valueKey);
    when(rs.getString("label")).thenReturn(label);
    when(rs.getString("value_type")).thenReturn(valueType);
    when(rs.getInt("sort_order")).thenReturn(sortOrder);
    when(rs.getBoolean("disabled")).thenReturn(disabled);
    return mapper.mapRow(rs, 0);
  }

  private Object mapMapping(
      RowMapper<?> mapper,
      Long id,
      Long valueId,
      String rawValue,
      String sourceInstance,
      String unmappedReason)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(id);
    when(rs.getLong("value_id")).thenReturn(valueId);
    when(rs.getString("raw_value")).thenReturn(rawValue);
    when(rs.getString("source_instance")).thenReturn(sourceInstance);
    when(rs.getString("unmapped_reason")).thenReturn(unmappedReason);
    return mapper.mapRow(rs, 0);
  }
}
