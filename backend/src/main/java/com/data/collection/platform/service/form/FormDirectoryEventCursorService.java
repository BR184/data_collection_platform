package com.data.collection.platform.service.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表单目录事件游标：按事件顺序唤醒受影响资源的变更 head，并与游标同事务提交。
 *
 * <p>事件只声明范围与发布版本；受影响资源取该版本目录中真实存在的资源，避免把整个项目全部
 * 置为待消费。游标单调前进，事件重复投递只推进游标、不重复唤醒；旧事件按 CONSUMED 收口。
 */
@Service
public class FormDirectoryEventCursorService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public FormDirectoryEventCursorService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * 消费一批待处理目录事件。
   *
   * @param limit 单批上限
   * @return 实际处理的事件数（含重复投递的游标推进）
   */
  @Transactional
  public int consumePending(int limit) {
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            """
            select id, scope_id, source_set_revision, payload::text as payload
              from form_directory_events
             where status in ('PENDING', 'RETRY_WAIT')
             order by id
             for update skip locked
             limit ?
            """,
            limit);
    int consumed = 0;
    for (Map<String, Object> event : events) {
      long eventId = ((Number) event.get("id")).longValue();
      long scopeId = ((Number) event.get("scope_id")).longValue();
      long revision = ((Number) event.get("source_set_revision")).longValue();
      jdbcTemplate.update(
          "insert into form_directory_event_cursors(scope_id, last_consumed_event_id)"
              + " values (?, 0) on conflict (scope_id) do nothing",
          scopeId);
      Long cursor =
          jdbcTemplate.queryForObject(
              "select last_consumed_event_id from form_directory_event_cursors"
                  + " where scope_id = ? for update",
              Long.class,
              scopeId);
      if (cursor == null || eventId > cursor) {
        Long versionId = publishedVersionId((String) event.get("payload"));
        if (versionId == null) {
          versionId =
              jdbcTemplate.queryForObject(
                  "select current_version_id from form_template_scopes where id = ?",
                  Long.class,
                  scopeId);
        }
        if (versionId != null) {
          String hash =
              jdbcTemplate.queryForObject(
                  "select source_set_hash from form_template_scopes where id = ?",
                  String.class,
                  scopeId);
          jdbcTemplate.update(
              """
              update form_change_heads h
                 set latest_change_version = h.latest_change_version + 1,
                     status = 'PENDING',
                     pending_storage_version_id = ?,
                     pending_source_set_revision = ?,
                     pending_source_set_hash = ?,
                     pending_authority_revision = a.authority_revision,
                     updated_at = clock_timestamp()
                from form_resource_authorities a
               where a.source_config_id = h.source_config_id
                 and a.project_id = h.project_id
                 and a.resource_type = h.resource_type
                 and a.resource_id = h.resource_id
                 and a.template_key = h.template_key
                 and a.scope_id = h.scope_id
                 and a.authority = 'FORM'
                 and h.scope_id = ?
                 and exists (
                   select 1 from form_record_locations l
                    where l.scope_id = h.scope_id
                      and l.storage_version_id = ?
                      and l.resource_type = h.resource_type
                      and l.project_id = h.project_id
                      and l.resource_id = h.resource_id::text)
              """,
              versionId,
              revision,
              hash,
              scopeId,
              versionId);
          jdbcTemplate.update(
              "update form_directory_event_cursors set last_consumed_event_id = ?,"
                  + " updated_at = clock_timestamp() where scope_id = ?",
              eventId,
              scopeId);
        }
      }
      jdbcTemplate.update(
          "update form_directory_events set status = 'CONSUMED',"
              + " consumed_at = clock_timestamp(), lease_owner = null, lease_until = null"
              + " where id = ?",
          eventId);
      consumed++;
    }
    return consumed;
  }

  private Long publishedVersionId(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(payload);
      for (String field : List.of("versionId", "toVersionId")) {
        JsonNode value = node.get(field);
        if (value != null && value.canConvertToLong()) {
          return value.asLong();
        }
      }
      return null;
    } catch (Exception error) {
      throw new IllegalStateException("表单目录事件载荷无法解析", error);
    }
  }
}
