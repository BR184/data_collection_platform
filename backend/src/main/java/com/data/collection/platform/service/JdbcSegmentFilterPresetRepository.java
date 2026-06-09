package com.data.collection.platform.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSegmentFilterPresetRepository implements SegmentFilterPresetRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcSegmentFilterPresetRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public SegmentFilterPreset save(SegmentFilterPreset preset) {
    if (preset.id() <= 0) {
      return insert(preset);
    }
    update(preset);
    return findById(preset.id()).orElseThrow();
  }

  @Override
  public Optional<SegmentFilterPreset> findById(long id) {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              baseSelectSql() + " where id = ?",
              this::mapPreset,
              id));
    } catch (EmptyResultDataAccessException exception) {
      return Optional.empty();
    }
  }

  @Override
  public List<SegmentFilterPreset> findAll() {
    return jdbcTemplate.query(baseSelectSql(), this::mapPreset);
  }

  @Override
  public void delete(long id) {
    jdbcTemplate.update("delete from segment_filter_preset where id = ?", id);
  }

  private SegmentFilterPreset insert(SegmentFilterPreset preset) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  insert into segment_filter_preset(
                    preset_name,
                    owner_user_id,
                    visibility,
                    entity_type,
                    scenario_key,
                    scope_key,
                    dsl_json,
                    dsl_hash,
                    tag_schema_hash,
                    source_data_watermark_at_save,
                    last_used_at,
                    created_at,
                    updated_at
                  ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                  """,
                  new String[] {"id"});
          bindMutableFields(statement, preset);
          statement.setTimestamp(12, toTimestamp(preset.createdAt()));
          statement.setTimestamp(13, toTimestamp(preset.updatedAt()));
          return statement;
        },
        keyHolder);
    Number key = keyHolder.getKey();
    return findById(key == null ? 0 : key.longValue()).orElseThrow();
  }

  private void update(SegmentFilterPreset preset) {
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  update segment_filter_preset
                     set preset_name = ?,
                         owner_user_id = ?,
                         visibility = ?,
                         entity_type = ?,
                         scenario_key = ?,
                         scope_key = ?,
                         dsl_json = ?,
                         dsl_hash = ?,
                         tag_schema_hash = ?,
                         source_data_watermark_at_save = ?,
                         last_used_at = ?,
                         updated_at = ?
                   where id = ?
                  """);
          bindMutableFields(statement, preset);
          statement.setTimestamp(12, toTimestamp(preset.updatedAt()));
          statement.setLong(13, preset.id());
          return statement;
        });
  }

  private void bindMutableFields(PreparedStatement statement, SegmentFilterPreset preset)
      throws SQLException {
    statement.setString(1, preset.presetName());
    statement.setString(2, preset.ownerUserId());
    statement.setString(3, preset.visibility());
    statement.setString(4, preset.entityType());
    statement.setString(5, preset.scenarioKey());
    statement.setString(6, preset.scopeKey());
    statement.setString(7, preset.dslJson());
    statement.setString(8, preset.dslHash());
    statement.setString(9, preset.tagSchemaHash());
    statement.setTimestamp(10, toTimestamp(parseWatermark(preset.sourceDataWatermarkAtSave())));
    statement.setTimestamp(11, toTimestamp(preset.lastUsedAt()));
  }

  private SegmentFilterPreset mapPreset(ResultSet rs, int rowNum) throws SQLException {
    return new SegmentFilterPreset(
        rs.getLong("id"),
        rs.getString("preset_name"),
        rs.getString("owner_user_id"),
        rs.getString("visibility"),
        rs.getString("entity_type"),
        rs.getString("scenario_key"),
        rs.getString("scope_key"),
        rs.getString("dsl_json"),
        rs.getString("dsl_hash"),
        rs.getString("tag_schema_hash"),
        toText(rs.getTimestamp("source_data_watermark_at_save")),
        toLocalDateTime(rs.getTimestamp("last_used_at")),
        toLocalDateTime(rs.getTimestamp("created_at")),
        toLocalDateTime(rs.getTimestamp("updated_at")));
  }

  private String baseSelectSql() {
    return """
        select id,
               preset_name,
               owner_user_id,
               visibility,
               entity_type,
               scenario_key,
               scope_key,
               dsl_json,
               dsl_hash,
               tag_schema_hash,
               source_data_watermark_at_save,
               last_used_at,
               created_at,
               updated_at
          from segment_filter_preset
        """;
  }

  private Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  private LocalDateTime toLocalDateTime(Timestamp value) {
    return value == null ? null : value.toLocalDateTime();
  }

  private String toText(Timestamp value) {
    LocalDateTime localDateTime = toLocalDateTime(value);
    return localDateTime == null ? null : localDateTime.toString();
  }

  private LocalDateTime parseWatermark(String value) {
    return value == null || value.isBlank() ? null : LocalDateTime.parse(value.trim());
  }
}
