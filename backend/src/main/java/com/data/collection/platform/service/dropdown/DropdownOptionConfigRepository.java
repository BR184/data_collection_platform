package com.data.collection.platform.service.dropdown;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 下拉框配置与字段绑定的唯一持久层。
 *
 * <p>配置内容整行替换 + 版本乐观锁；绑定以 field_key 为主键 upsert，改绑推进 bound_at
 * 供缓存指纹感知绑定关系变化。表结构见迁移 V20260903_01。
 */
@Repository
public class DropdownOptionConfigRepository {
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public DropdownOptionConfigRepository(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  /** 单份配置的解析态：双套规则与手动选项均已从 JSON 载入。 */
  public record StoredConfig(long id, DropdownOptionRulesPayload rules, List<String> manualOptions, long version) {}

  /** 字段绑定行。 */
  public record FieldBinding(String fieldKey, long configId) {}

  /**
   * 读取配置内容。
   *
   * @param configId 配置 ID
   * @return 解析后的配置；不存在时为空
   */
  public Optional<StoredConfig> loadConfig(long configId) {
    List<StoredConfig> rows =
        jdbcTemplate.query(
            """
            select id, rules_json, manual_options_json, version
              from dropdown_option_configs
             where id = ?
            """,
            (rs, rowNum) -> {
              DropdownOptionRulesPayload rules =
                  jsonUtils.fromJson(rs.getString("rules_json"), DropdownOptionRulesPayload.class);
              return new StoredConfig(
                  rs.getLong("id"),
                  rules == null ? DropdownOptionRulesPayload.empty() : rules.withNullsAsEmpty(),
                  normalizeManual(jsonUtils.toStringList(rs.getString("manual_options_json"))),
                  rs.getLong("version"));
            },
            configId);
    return rows.stream().findFirst();
  }

  /**
   * 读取字段当前绑定的配置 ID。
   *
   * @param fieldKey 注册字段键
   * @return 绑定的配置 ID；字段未绑定时为空
   */
  public Optional<Long> findBoundConfigId(String fieldKey) {
    List<Long> rows =
        jdbcTemplate.queryForList(
            "select config_id from dropdown_option_field_bindings where field_key = ?",
            Long.class,
            fieldKey);
    return rows.stream().findFirst();
  }

  /**
   * 读取全部字段绑定。
   *
   * @return 按 field_key 排序的绑定行
   */
  public List<FieldBinding> loadAllBindings() {
    return jdbcTemplate.query(
        "select field_key, config_id from dropdown_option_field_bindings order by field_key",
        (rs, rowNum) -> new FieldBinding(rs.getString("field_key"), rs.getLong("config_id")));
  }

  /**
   * 判断配置是否存在（改绑到既有配置前的合法性校验）。
   *
   * @param configId 配置 ID
   * @return 是否存在
   */
  public boolean configExists(long configId) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            "select count(*) > 0 from dropdown_option_configs where id = ?",
            Boolean.class,
            configId);
    return Boolean.TRUE.equals(exists);
  }

  /**
   * 新建配置。
   *
   * @param rulesJson 已归一化的双套规则 JSON
   * @param manualOptionsJson 已归一化的手动选项 JSON
   * @param updatedBy 操作者用户名
   * @return 新配置 ID（版本从 0 开始）
   */
  public long insertConfig(String rulesJson, String manualOptionsJson, String updatedBy) {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into dropdown_option_configs(rules_json, manual_options_json, version, updated_by)
            values (?::jsonb, ?::jsonb, 0, ?)
            returning id
            """,
            Long.class,
            rulesJson,
            manualOptionsJson,
            updatedBy);
    if (id == null) {
      throw new IllegalStateException("下拉框配置创建失败：未返回配置 ID");
    }
    return id;
  }

  /**
   * 整行替换配置内容。
   *
   * @param configId 配置 ID
   * @param rulesJson 已归一化的双套规则 JSON
   * @param manualOptionsJson 已归一化的手动选项 JSON
   * @param expectedVersion 调用方持有的版本号；不匹配视为并发修改
   * @param updatedBy 操作者用户名
   * @return 更新后的新版本号
   * @throws BizException 配置不存在（NOT_FOUND）或版本冲突（CONFLICT）
   */
  public long updateConfig(
      long configId, String rulesJson, String manualOptionsJson, long expectedVersion, String updatedBy) {
    List<Long> updated =
        jdbcTemplate.query(
            """
            update dropdown_option_configs
               set rules_json = ?::jsonb,
                   manual_options_json = ?::jsonb,
                   version = version + 1,
                   updated_by = ?,
                   updated_at = current_timestamp
             where id = ?
               and version = ?
            returning version
            """,
            (rs, rowNum) -> rs.getLong("version"),
            rulesJson,
            manualOptionsJson,
            updatedBy,
            configId,
            expectedVersion);
    if (!updated.isEmpty()) {
      return updated.get(0);
    }
    if (!configExists(configId)) {
      throw new BizException(ResultCode.NOT_FOUND, "下拉框配置不存在或已被删除");
    }
    throw new BizException(ResultCode.CONFLICT, "配置已被他人修改，请刷新后重试");
  }

  /**
   * 复制既有配置内容为新配置（版本清零），供字段拆分使用。
   *
   * @param sourceConfigId 来源配置 ID
   * @param updatedBy 操作者用户名
   * @return 新配置 ID
   */
  public long copyConfig(long sourceConfigId, String updatedBy) {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into dropdown_option_configs(rules_json, manual_options_json, version, updated_by)
            select rules_json, manual_options_json, 0, ? from dropdown_option_configs where id = ?
            returning id
            """,
            Long.class,
            updatedBy,
            sourceConfigId);
    if (id == null) {
      throw new IllegalStateException("下拉框配置复制失败：未返回配置 ID");
    }
    return id;
  }

  /**
   * 将字段绑定到配置；已绑定则整体改绑（bound_at 推进）。
   *
   * @param fieldKey 注册字段键
   * @param configId 目标配置 ID
   * @param updatedBy 操作者用户名
   */
  public void bindField(String fieldKey, long configId, String updatedBy) {
    jdbcTemplate.update(
        """
        insert into dropdown_option_field_bindings(field_key, config_id, bound_at, bound_by)
        values (?, ?, current_timestamp, ?)
        on conflict (field_key) do update
           set config_id = excluded.config_id,
               bound_at = current_timestamp,
               bound_by = excluded.bound_by
        """,
        fieldKey,
        configId,
        updatedBy);
  }

  private static List<String> normalizeManual(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
