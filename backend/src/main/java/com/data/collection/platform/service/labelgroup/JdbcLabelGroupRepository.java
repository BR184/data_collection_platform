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
      String name, String dimensionKey, String groupType, String description, String username) {
    GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("name", name)
            .addValue("dimensionKey", dimensionKey)
            .addValue("groupType", groupType)
            .addValue("description", description)
            .addValue("username", username);
    jdbcTemplate.update(
        """
        insert into label_groups (name, dimension_key, group_type, description, created_by, updated_by)
        values (:name, :dimensionKey, :groupType, :description, :username, :username)
        """,
        params,
        keyHolder,
        new String[] {"id"});
    Number id = keyHolder.getKey();
    return findById(id == null ? null : id.longValue()).orElseThrow();
  }

  @Override
  public void replaceMembers(
      Long groupId, String dimensionKey, List<LabelGroupMemberRecord> members) {
    jdbcTemplate.update(
        "delete from label_group_members where group_id = :groupId",
        new MapSqlParameterSource("groupId", groupId));
    for (LabelGroupMemberRecord member : members) {
      jdbcTemplate.update(
          """
          insert into label_group_members
            (group_id, dimension_key, member_value, display_name, sort_order)
          values (:groupId, :dimensionKey, :memberValue, :displayName, :sortOrder)
          """,
          new MapSqlParameterSource()
              .addValue("groupId", groupId)
              .addValue("dimensionKey", dimensionKey)
              .addValue("memberValue", member.memberValue())
              .addValue("displayName", member.displayName())
              .addValue("sortOrder", member.sortOrder()));
    }
  }

  @Override
  public Optional<LabelGroupRecord> findById(Long groupId) {
    if (groupId == null) {
      return Optional.empty();
    }
    List<LabelGroupRecord> groups =
        loadGroups(
            """
            select id, name, dimension_key, group_type, description, enabled,
                   created_by, created_at, updated_by, updated_at
            from label_groups
            where id = :groupId
            """,
            new MapSqlParameterSource("groupId", groupId));
    return groups.stream().findFirst();
  }

  @Override
  public List<LabelGroupRecord> list(String dimensionKey, String keyword, Boolean enabled) {
    StringBuilder sql =
        new StringBuilder(
            """
            select id, name, dimension_key, group_type, description, enabled,
                   created_by, created_at, updated_by, updated_at
            from label_groups
            where 1 = 1
            """);
    MapSqlParameterSource params = new MapSqlParameterSource();
    if (dimensionKey != null && !dimensionKey.isBlank()) {
      sql.append(" and dimension_key = :dimensionKey");
      params.addValue("dimensionKey", dimensionKey);
    }
    if (keyword != null && !keyword.isBlank()) {
      sql.append(" and lower(name) like lower(:keyword)");
      params.addValue("keyword", "%" + keyword.trim() + "%");
    }
    if (enabled != null) {
      sql.append(" and enabled = :enabled");
      params.addValue("enabled", enabled);
    }
    sql.append(" order by dimension_key asc, updated_at desc, id desc");
    return loadGroups(sql.toString(), params);
  }

  @Override
  public boolean existsByDimensionAndName(String dimensionKey, String name, Long excludeId) {
    String sql =
        """
        select count(1)
        from label_groups
        where dimension_key = :dimensionKey
          and name = :name
          and (:excludeId is null or id <> :excludeId)
        """;
    Integer count =
        jdbcTemplate.queryForObject(
            sql,
            new MapSqlParameterSource()
                .addValue("dimensionKey", dimensionKey)
                .addValue("name", name)
                .addValue("excludeId", excludeId),
            Integer.class);
    return count != null && count > 0;
  }

  @Override
  public void updateGroup(
      Long groupId, String name, String description, boolean enabled, String username) {
    jdbcTemplate.update(
        """
        update label_groups
        set name = :name,
            description = :description,
            enabled = :enabled,
            updated_by = :username,
            updated_at = now()
        where id = :groupId
        """,
        new MapSqlParameterSource()
            .addValue("groupId", groupId)
            .addValue("name", name)
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
      LabelGroupRecord group = mapGroup(rs, List.of());
      groups.put(group.id(), group);
    });
    if (groups.isEmpty()) {
      return List.of();
    }
    List<LabelGroupMemberRecord> members =
        jdbcTemplate.query(
            """
            select id, group_id, dimension_key, member_value, display_name, sort_order
            from label_group_members
            where group_id in (:groupIds)
            order by group_id asc, sort_order asc, id asc
            """,
            new MapSqlParameterSource("groupIds", groups.keySet()),
            this::mapMember);
    Map<Long, List<LabelGroupMemberRecord>> byGroup =
        members.stream().collect(java.util.stream.Collectors.groupingBy(
            LabelGroupMemberRecord::groupId,
            LinkedHashMap::new,
            java.util.stream.Collectors.toList()));
    return groups.values().stream()
        .map(group -> new LabelGroupRecord(
            group.id(),
            group.name(),
            group.dimensionKey(),
            group.groupType(),
            group.description(),
            group.enabled(),
            group.createdBy(),
            group.createdAt(),
            group.updatedBy(),
            group.updatedAt(),
            byGroup.getOrDefault(group.id(), List.of())))
        .toList();
  }

  private LabelGroupRecord mapGroup(ResultSet rs, List<LabelGroupMemberRecord> members)
      throws SQLException {
    return new LabelGroupRecord(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("dimension_key"),
        rs.getString("group_type"),
        rs.getString("description"),
        rs.getBoolean("enabled"),
        rs.getString("created_by"),
        toOffsetDateTime(rs.getTimestamp("created_at")),
        rs.getString("updated_by"),
        toOffsetDateTime(rs.getTimestamp("updated_at")),
        members);
  }

  private LabelGroupMemberRecord mapMember(ResultSet rs, int rowNum) throws SQLException {
    return new LabelGroupMemberRecord(
        rs.getLong("id"),
        rs.getLong("group_id"),
        rs.getString("dimension_key"),
        rs.getString("member_value"),
        rs.getString("display_name"),
        rs.getInt("sort_order"));
  }

  private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }
}
