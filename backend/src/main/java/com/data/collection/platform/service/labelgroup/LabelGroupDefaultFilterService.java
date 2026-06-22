package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupDefaultFilterResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupDefaultFilterUpdateRequest;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LabelGroupDefaultFilterService {
  private static final String MODULE_FIELD = "moduleName";
  private static final Map<String, String> PAGE_NAMES =
      Map.of("system-test-defect-summary", "系统测试缺陷汇总");
  private static final Map<String, String> FIELD_NAMES =
      Map.of(MODULE_FIELD, "模块");
  private static final Map<String, String> FIELD_VALUE_TYPES =
      Map.of(MODULE_FIELD, "STRING");

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public LabelGroupDefaultFilterService(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<LabelGroupDefaultFilterResponse> listSupportedDefaults() {
    return supportedPairs().stream()
        .map(pair -> findRecord(pair.pageKey(), pair.fieldKey())
            .map(this::toResponse)
            .orElseGet(() -> emptyResponse(pair.pageKey(), pair.fieldKey())))
        .toList();
  }

  public Optional<StatisticFilterCondition> defaultCondition(String pageKey, String fieldKey) {
    return findRecord(pageKey, fieldKey)
        .filter(record -> record.enabled()
            && record.labelGroupId() != null
            && StringUtils.hasText(record.labelGroupValueType()))
        .map(record -> new StatisticFilterCondition(
            record.fieldKey(),
            normalizeOperator(record.operator()),
            null,
            null,
            "LABEL_GROUP",
            record.labelGroupId(),
            record.labelGroupName(),
            List.of()));
  }

  @Transactional
  public LabelGroupDefaultFilterResponse save(LabelGroupDefaultFilterUpdateRequest request) {
    String pageKey = requireSupportedPage(request.pageKey());
    String fieldKey = requireSupportedField(request.fieldKey());
    Long labelGroupId = request.labelGroupId();
    if (labelGroupId != null) {
      String expectedType = FIELD_VALUE_TYPES.get(fieldKey);
      Integer count =
          jdbcTemplate.queryForObject(
              """
              select count(1)
                from label_groups
               where id = :labelGroupId
                 and enabled = true
                 and value_type = :valueType
              """,
              new MapSqlParameterSource()
                  .addValue("labelGroupId", labelGroupId)
                  .addValue("valueType", expectedType),
              Integer.class);
      if (count == null || count == 0) {
        throw new BizException("默认筛选只能选择已启用且值类型匹配的标签组");
      }
    }
    jdbcTemplate.update(
        """
        insert into label_group_default_filters
          (page_key, field_key, operator, label_group_id, enabled, description, created_by, updated_by)
        values
          (:pageKey, :fieldKey, :operator, :labelGroupId, :enabled, :description, 'system', 'system')
        on conflict (page_key, field_key)
        do update set
          operator = excluded.operator,
          label_group_id = excluded.label_group_id,
          enabled = excluded.enabled,
          description = excluded.description,
          updated_by = excluded.updated_by,
          updated_at = now()
        """,
        new MapSqlParameterSource()
            .addValue("pageKey", pageKey)
            .addValue("fieldKey", fieldKey)
            .addValue("operator", normalizeOperator(request.operator()))
            .addValue("labelGroupId", labelGroupId)
            .addValue("enabled", request.enabled() == null || request.enabled())
            .addValue("description", trimToNull(request.description())));
    return findRecord(pageKey, fieldKey).map(this::toResponse).orElseGet(() -> emptyResponse(pageKey, fieldKey));
  }

  private Optional<LabelGroupDefaultFilterRecord> findRecord(String pageKey, String fieldKey) {
    if (!isSupported(pageKey, fieldKey)) {
      return Optional.empty();
    }
    List<LabelGroupDefaultFilterRecord> records =
        jdbcTemplate.query(
            """
            select f.id, f.page_key, f.field_key, f.operator, f.label_group_id,
                   g.name as label_group_name, g.value_type as label_group_value_type,
                   f.enabled, f.description, f.updated_at
              from label_group_default_filters f
              left join label_groups g on g.id = f.label_group_id
             where f.page_key = :pageKey and f.field_key = :fieldKey
            """,
            new MapSqlParameterSource()
                .addValue("pageKey", pageKey)
                .addValue("fieldKey", fieldKey),
            this::mapRecord);
    return records.stream().findFirst();
  }

  private LabelGroupDefaultFilterRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
    return new LabelGroupDefaultFilterRecord(
        rs.getLong("id"),
        rs.getString("page_key"),
        rs.getString("field_key"),
        rs.getString("operator"),
        longOrNull(rs, "label_group_id"),
        rs.getString("label_group_name"),
        rs.getString("label_group_value_type"),
        rs.getBoolean("enabled"),
        rs.getString("description"),
        toOffsetDateTime(rs.getTimestamp("updated_at")));
  }

  private LabelGroupDefaultFilterResponse toResponse(LabelGroupDefaultFilterRecord record) {
    return new LabelGroupDefaultFilterResponse(
        record.id(),
        record.pageKey(),
        pageName(record.pageKey()),
        record.fieldKey(),
        fieldName(record.fieldKey()),
        normalizeOperator(record.operator()),
        record.labelGroupId(),
        record.labelGroupName(),
        record.labelGroupValueType(),
        record.enabled(),
        record.description(),
        record.updatedAt());
  }

  private LabelGroupDefaultFilterResponse emptyResponse(String pageKey, String fieldKey) {
    return new LabelGroupDefaultFilterResponse(
        null,
        pageKey,
        pageName(pageKey),
        fieldKey,
        fieldName(fieldKey),
        "intersects",
        null,
        null,
        FIELD_VALUE_TYPES.get(fieldKey),
        false,
        null,
        null);
  }

  private List<SupportedPair> supportedPairs() {
    return List.of(new SupportedPair("system-test-defect-summary", MODULE_FIELD));
  }

  private boolean isSupported(String pageKey, String fieldKey) {
    return supportedPairs().stream().anyMatch(pair -> pair.pageKey().equals(pageKey) && pair.fieldKey().equals(fieldKey));
  }

  private String requireSupportedPage(String pageKey) {
    String safePageKey = trimToNull(pageKey);
    if (safePageKey == null || !PAGE_NAMES.containsKey(safePageKey)) {
      throw new BizException("不支持的默认筛选页面：" + pageKey);
    }
    return safePageKey;
  }

  private String requireSupportedField(String fieldKey) {
    String safeFieldKey = trimToNull(fieldKey);
    if (safeFieldKey == null || !FIELD_VALUE_TYPES.containsKey(safeFieldKey)) {
      throw new BizException("不支持的默认筛选字段：" + fieldKey);
    }
    return safeFieldKey;
  }

  private String normalizeOperator(String operator) {
    String safeOperator = trimToNull(operator);
    if (safeOperator == null || "eq".equals(safeOperator)) {
      return "intersects";
    }
    if ("ne".equals(safeOperator)) {
      return "notIntersects";
    }
    if (List.of("intersects", "notIntersects", "containsAll", "notContainsAll").contains(safeOperator)) {
      return safeOperator;
    }
    return "intersects";
  }

  private String pageName(String pageKey) {
    return PAGE_NAMES.getOrDefault(pageKey, pageKey);
  }

  private String fieldName(String fieldKey) {
    return FIELD_NAMES.getOrDefault(fieldKey, fieldKey);
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private Long longOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  private record SupportedPair(String pageKey, String fieldKey) {}
}
