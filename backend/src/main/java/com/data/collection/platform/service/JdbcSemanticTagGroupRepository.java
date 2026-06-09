package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSemanticTagGroupRepository implements SemanticTagGroupRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcSemanticTagGroupRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<SemanticTagGroupDefinition> listEnabledGroups(String entityType) {
    List<GroupRow> groupRows = jdbcTemplate.query(
        """
        select id,
               domain,
               group_key,
               label,
               source_mode,
               rule_policy_key,
               selection_mode,
               match_strategy_name,
               enabled,
               sort_order
          from semantic_tag_group
         where domain = ?
           and enabled = true
         order by sort_order, group_key
        """,
        this::mapGroup,
        entityType);
    if (groupRows.isEmpty()) {
      return List.of();
    }

    Map<Long, GroupBuilder> groupsById = new LinkedHashMap<>();
    groupRows.forEach(row -> groupsById.put(row.id(), new GroupBuilder(row)));
    List<ValueRow> valueRows = jdbcTemplate.query(
        """
        select group_id,
               value_key,
               label,
               value_type,
               canonical_value,
               enabled,
               sort_order
          from semantic_tag_value
         where group_id in (%s)
           and enabled = true
         order by group_id, sort_order, value_key
        """.formatted(placeholders(groupRows.size())),
        this::mapValue,
        groupRows.stream().map(GroupRow::id).toArray());
    valueRows.forEach(row -> {
      GroupBuilder group = groupsById.get(row.groupId());
      if (group != null) {
        group.values().add(new SemanticTagValueDefinition(
            row.valueKey(),
            row.label(),
            row.valueType(),
            row.canonicalValue(),
            row.enabled(),
            row.sortOrder()));
      }
    });
    return groupsById.values().stream()
        .map(GroupBuilder::build)
        .toList();
  }

  private GroupRow mapGroup(ResultSet rs, int rowNum) throws SQLException {
    return new GroupRow(
        rs.getLong("id"),
        rs.getString("domain"),
        rs.getString("group_key"),
        rs.getString("label"),
        rs.getString("source_mode"),
        rs.getString("rule_policy_key"),
        rs.getString("selection_mode"),
        rs.getString("match_strategy_name"),
        rs.getBoolean("enabled"),
        rs.getInt("sort_order"));
  }

  private ValueRow mapValue(ResultSet rs, int rowNum) throws SQLException {
    String canonicalValue = rs.getString("canonical_value");
    String valueKey = rs.getString("value_key");
    return new ValueRow(
        rs.getLong("group_id"),
        valueKey,
        rs.getString("label"),
        rs.getString("value_type"),
        canonicalValue == null ? valueKey : canonicalValue,
        rs.getBoolean("enabled"),
        rs.getInt("sort_order"));
  }

  private String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private record GroupRow(
      long id,
      String domain,
      String groupKey,
      String label,
      String sourceMode,
      String rulePolicyKey,
      String selectionMode,
      String matchStrategyName,
      boolean enabled,
      int sortOrder) {
  }

  private record ValueRow(
      long groupId,
      String valueKey,
      String label,
      String valueType,
      String canonicalValue,
      boolean enabled,
      int sortOrder) {
  }

  private record GroupBuilder(GroupRow group, List<SemanticTagValueDefinition> values) {
    private GroupBuilder(GroupRow group) {
      this(group, new ArrayList<>());
    }

    private SemanticTagGroupDefinition build() {
      return new SemanticTagGroupDefinition(
          group.domain(),
          group.groupKey(),
          group.label(),
          group.sourceMode(),
          group.rulePolicyKey(),
          group.selectionMode(),
          group.matchStrategyName(),
          group.enabled(),
          group.sortOrder(),
          values);
    }
  }
}
