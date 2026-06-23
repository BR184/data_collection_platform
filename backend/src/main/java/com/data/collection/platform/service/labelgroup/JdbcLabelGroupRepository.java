package com.data.collection.platform.service.labelgroup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLabelGroupRepository implements LabelGroupRepository {
  private final NamedParameterJdbcTemplate jdbcTemplate;

  JdbcLabelGroupRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public LabelGroupRecord createGroup(
      String name,
      String valueType,
      String groupType,
      String applicableScope,
      String sourceFieldKey,
      String description,
      String username) {
    GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("name", name)
            .addValue("valueType", valueType)
            .addValue("groupType", groupType)
            .addValue("applicableScope", applicableScope)
            .addValue("sourceFieldKey", sourceFieldKey)
            .addValue("description", description)
            .addValue("username", username);
    jdbcTemplate.update(
        """
        insert into label_groups
          (name, value_type, group_type, applicable_scope, source_field_key, description, created_by, updated_by)
        values
          (:name, :valueType, :groupType, :applicableScope, :sourceFieldKey, :description, :username, :username)
        """,
        params,
        keyHolder,
        new String[] {"id"});
    Number id = keyHolder.getKey();
    return findById(id == null ? null : id.longValue()).orElseThrow();
  }

  @Override
  public void replaceMembers(Long groupId, List<LabelGroupMemberRecord> members) {
    jdbcTemplate.update(
        "delete from label_group_members where group_id = :groupId",
        new MapSqlParameterSource("groupId", groupId));
    for (LabelGroupMemberRecord member : members) {
      jdbcTemplate.update(
          """
          insert into label_group_members
            (group_id, member_value, display_name, sort_order)
          values (:groupId, :memberValue, :displayName, :sortOrder)
          """,
          new MapSqlParameterSource()
              .addValue("groupId", groupId)
              .addValue("memberValue", member.memberValue())
              .addValue("displayName", member.displayName())
              .addValue("sortOrder", member.sortOrder()));
    }
  }

  @Override
  public void replaceReferences(Long groupId, List<Long> childGroupIds) {
    jdbcTemplate.update(
        "delete from label_group_references where parent_group_id = :groupId",
        new MapSqlParameterSource("groupId", groupId));
    int sortOrder = 0;
    for (Long childGroupId : childGroupIds) {
      jdbcTemplate.update(
          """
          insert into label_group_references (parent_group_id, child_group_id, sort_order)
          values (:groupId, :childGroupId, :sortOrder)
          """,
          new MapSqlParameterSource()
              .addValue("groupId", groupId)
              .addValue("childGroupId", childGroupId)
              .addValue("sortOrder", sortOrder++));
    }
  }

  @Override
  public void replaceDynamicRule(Long groupId, LabelGroupDynamicRuleRecord rule) {
    jdbcTemplate.update(
        "delete from label_group_dynamic_rules where group_id = :groupId",
        new MapSqlParameterSource("groupId", groupId));
    if (rule == null) {
      return;
    }
    jdbcTemplate.update(
        """
        insert into label_group_dynamic_rules
          (group_id, rule_config_json, output_value_type,
           last_status, last_error, last_computed_at)
        values (:groupId, :ruleConfigJson, :outputValueType,
                :lastStatus, :lastError, :lastComputedAt)
        """,
        new MapSqlParameterSource()
            .addValue("groupId", groupId)
            .addValue("ruleConfigJson", rule.ruleConfigJson())
            .addValue("outputValueType", rule.outputValueType())
            .addValue("lastStatus", rule.lastStatus())
            .addValue("lastError", rule.lastError())
            .addValue("lastComputedAt", toTimestamp(rule.lastComputedAt())));
  }

  @Override
  public Optional<LabelGroupRecord> findById(Long groupId) {
    if (groupId == null) {
      return Optional.empty();
    }
    List<LabelGroupRecord> groups =
        loadGroups(
            """
            select id, name, value_type, group_type, description, enabled,
                   applicable_scope, source_field_key,
                   created_by, created_at, updated_by, updated_at
            from label_groups
            where id = :groupId
            """,
            new MapSqlParameterSource("groupId", groupId));
    return groups.stream().findFirst();
  }

  @Override
  public List<LabelGroupRecord> list(String valueType, String keyword, Boolean enabled) {
    StringBuilder sql =
        new StringBuilder(
            """
            select id, name, value_type, group_type, description, enabled,
                   applicable_scope, source_field_key,
                   created_by, created_at, updated_by, updated_at
            from label_groups
            where 1 = 1
            """);
    MapSqlParameterSource params = new MapSqlParameterSource();
    if (valueType != null && !valueType.isBlank()) {
      sql.append(" and value_type = :valueType");
      params.addValue("valueType", valueType);
    }
    if (keyword != null && !keyword.isBlank()) {
      sql.append(" and lower(name) like lower(:keyword)");
      params.addValue("keyword", "%" + keyword.trim() + "%");
    }
    if (enabled != null) {
      sql.append(" and enabled = :enabled");
      params.addValue("enabled", enabled);
    }
    sql.append(" order by updated_at desc, id desc");
    return loadGroups(sql.toString(), params);
  }

  @Override
  public boolean existsByName(String name, Long excludeId) {
    StringBuilder sql =
        new StringBuilder(
            """
            select count(1)
            from label_groups
            where name = :name
            """);
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("name", name);
    if (excludeId != null) {
      sql.append(" and id <> :excludeId");
      params.addValue("excludeId", excludeId);
    }
    Integer count =
        jdbcTemplate.queryForObject(
            sql.toString(),
            params,
            Integer.class);
    return count != null && count > 0;
  }

  @Override
  public void updateGroup(
      Long groupId,
      String name,
      String valueType,
      String groupType,
      String applicableScope,
      String sourceFieldKey,
      String description,
      boolean enabled,
      String username) {
    jdbcTemplate.update(
        """
        update label_groups
        set name = :name,
            value_type = :valueType,
            group_type = :groupType,
            applicable_scope = :applicableScope,
            source_field_key = :sourceFieldKey,
            description = :description,
            enabled = :enabled,
            updated_by = :username,
            updated_at = now()
        where id = :groupId
        """,
        new MapSqlParameterSource()
            .addValue("groupId", groupId)
            .addValue("name", name)
            .addValue("valueType", valueType)
            .addValue("groupType", groupType)
            .addValue("applicableScope", applicableScope)
            .addValue("sourceFieldKey", sourceFieldKey)
            .addValue("description", description)
            .addValue("enabled", enabled)
            .addValue("username", username));
  }

  @Override
  public void deleteById(Long groupId) {
    jdbcTemplate.update(
        "delete from label_groups where id = :groupId",
        new MapSqlParameterSource("groupId", groupId));
  }

  private List<LabelGroupRecord> loadGroups(String sql, MapSqlParameterSource params) {
    Map<Long, LabelGroupRecord> groups = new LinkedHashMap<>();
    jdbcTemplate.query(sql, params, rs -> {
      LabelGroupRecord group = mapGroup(rs, List.of(), List.of(), null);
      groups.put(group.id(), group);
    });
    if (groups.isEmpty()) {
      return List.of();
    }
    List<LabelGroupMemberRecord> members =
        jdbcTemplate.query(
            """
            select id, group_id, member_value, display_name, sort_order
            from label_group_members
            where group_id in (:groupIds)
            order by group_id asc, sort_order asc, id asc
            """,
            new MapSqlParameterSource("groupIds", groups.keySet()),
            this::mapMember);
    List<LabelGroupChildRecord> children =
        jdbcTemplate.query(
            """
            select r.id, r.parent_group_id, r.child_group_id, r.sort_order,
                   g.name as child_name, g.group_type as child_group_type,
                   g.value_type as child_value_type, g.enabled as child_enabled
              from label_group_references r
              join label_groups g on g.id = r.child_group_id
             where r.parent_group_id in (:groupIds)
             order by r.parent_group_id asc, r.sort_order asc, r.id asc
            """,
            new MapSqlParameterSource("groupIds", groups.keySet()),
            this::mapChild);
    List<LabelGroupDynamicRuleRecord> dynamicRules =
        jdbcTemplate.query(
            """
            select id, group_id, rule_config_json, output_value_type,
                   last_status, last_error, last_computed_at
            from label_group_dynamic_rules
            where group_id in (:groupIds)
            """,
            new MapSqlParameterSource("groupIds", groups.keySet()),
            this::mapDynamicRule);
    Map<Long, List<LabelGroupMemberRecord>> byGroup =
        members.stream().collect(java.util.stream.Collectors.groupingBy(
            LabelGroupMemberRecord::groupId,
            LinkedHashMap::new,
            java.util.stream.Collectors.toList()));
    Map<Long, List<LabelGroupChildRecord>> childrenByGroup =
        children.stream().collect(java.util.stream.Collectors.groupingBy(
            LabelGroupChildRecord::parentGroupId,
            LinkedHashMap::new,
            java.util.stream.Collectors.toList()));
    Map<Long, LabelGroupDynamicRuleRecord> dynamicRuleByGroup =
        dynamicRules.stream().collect(java.util.stream.Collectors.toMap(
            LabelGroupDynamicRuleRecord::groupId,
            rule -> rule,
            (left, right) -> left,
            LinkedHashMap::new));
    return groups.values().stream()
        .map(group -> new LabelGroupRecord(
            group.id(),
            group.name(),
            group.valueType(),
            group.groupType(),
            group.applicableScope(),
            group.sourceFieldKey(),
            group.description(),
            group.enabled(),
            group.createdBy(),
            group.createdAt(),
            group.updatedBy(),
            group.updatedAt(),
            byGroup.getOrDefault(group.id(), List.of()),
            childrenByGroup.getOrDefault(group.id(), List.of()),
            dynamicRuleByGroup.get(group.id())))
        .toList();
  }

  private LabelGroupRecord mapGroup(
      ResultSet rs,
      List<LabelGroupMemberRecord> members,
      List<LabelGroupChildRecord> children,
      LabelGroupDynamicRuleRecord dynamicRule)
      throws SQLException {
    return new LabelGroupRecord(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("value_type"),
        rs.getString("group_type"),
        rs.getString("applicable_scope"),
        rs.getString("source_field_key"),
        rs.getString("description"),
        rs.getBoolean("enabled"),
        rs.getString("created_by"),
        toOffsetDateTime(rs.getTimestamp("created_at")),
        rs.getString("updated_by"),
        toOffsetDateTime(rs.getTimestamp("updated_at")),
        members,
        children,
        dynamicRule);
  }

  private LabelGroupMemberRecord mapMember(ResultSet rs, int rowNum) throws SQLException {
    return new LabelGroupMemberRecord(
        rs.getLong("id"),
        rs.getLong("group_id"),
        rs.getString("member_value"),
        rs.getString("display_name"),
        rs.getInt("sort_order"));
  }

  private LabelGroupChildRecord mapChild(ResultSet rs, int rowNum) throws SQLException {
    return new LabelGroupChildRecord(
        rs.getLong("id"),
        rs.getLong("parent_group_id"),
        rs.getLong("child_group_id"),
        rs.getString("child_name"),
        rs.getString("child_group_type"),
        rs.getString("child_value_type"),
        rs.getBoolean("child_enabled"),
        rs.getInt("sort_order"));
  }

  private LabelGroupDynamicRuleRecord mapDynamicRule(ResultSet rs, int rowNum) throws SQLException {
    return new LabelGroupDynamicRuleRecord(
        rs.getLong("id"),
        rs.getLong("group_id"),
        rs.getString("rule_config_json"),
        rs.getString("output_value_type"),
        rs.getString("last_status"),
        rs.getString("last_error"),
        toOffsetDateTime(rs.getTimestamp("last_computed_at")));
  }

  private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  private Timestamp toTimestamp(OffsetDateTime value) {
    return value == null ? null : Timestamp.from(value.toInstant());
  }
}
