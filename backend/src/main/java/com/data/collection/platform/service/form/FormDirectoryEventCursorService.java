package com.data.collection.platform.service.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表单目录事件游标：按事件顺序唤醒受影响资源的变更 head，并与游标同事务提交。
 *
 * <p>最新源集合事件按范围完整 FORM 权威集合唤醒，包括只有旧记录或没有记录实例的资源；是否唤醒
 * 不再取决于空的新版本表是否已有行。范围行先锁定，随后按范围→head 锁序推进；比当前源集合更旧的
 * 事件只收口游标，不回退 head 的 pending 快照。未被公开的事实 head 作为持久补投依据，即使没有
 * 新事件或进程重启，每轮仍会重新提交既有 FACT_REFRESH。
 */
@Service
public class FormDirectoryEventCursorService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public FormDirectoryEventCursorService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /** 被目录事件唤醒的 FORM 资源；调用方据此登记既有事实任务。 */
  public record WokenResource(
      String sourceInstance, long projectId, String resourceType, long resourceId) {}

  /** 单批消费结果：事件数、新唤醒资源与待补投的未公开事实资源。 */
  public record Consumption(
      int consumed, List<WokenResource> woken, List<WokenResource> retry) {}

  /**
   * 消费一批待处理目录事件。
   *
   * @param limit 单批上限
   * @return 实际处理的事件数、新唤醒资源与持久补投资源
   */
  @Transactional
  public Consumption consumePending(int limit) {
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
    LinkedHashSet<WokenResource> woken = new LinkedHashSet<>();
    LinkedHashSet<String> wokenKeys = new LinkedHashSet<>();
    for (Map<String, Object> event : events) {
      long eventId = ((Number) event.get("id")).longValue();
      long scopeId = ((Number) event.get("scope_id")).longValue();
      long eventRevision = ((Number) event.get("source_set_revision")).longValue();
      Map<String, Object> scope =
          jdbcTemplate
              .queryForList(
                  "select current_version_id, source_set_revision, source_set_hash"
                      + " from form_template_scopes where id = ? for update",
                  scopeId)
              .stream()
              .findFirst()
              .orElse(null);
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
      if (scope != null && (cursor == null || eventId > cursor)) {
        long scopeRevision = ((Number) scope.get("source_set_revision")).longValue();
        Long currentVersionId = scope.get("current_version_id") == null
            ? null
            : ((Number) scope.get("current_version_id")).longValue();
        boolean latest = eventRevision >= scopeRevision;
        if (latest && currentVersionId != null) {
          String hash = (String) scope.get("source_set_hash");
          String wakeKey = scopeId + ":" + scopeRevision;
          if (wokenKeys.add(wakeKey)) {
            List<Map<String, Object>> targets = wakeHeads(
                scopeId, currentVersionId, scopeRevision, hash);
            for (Map<String, Object> target : targets) {
              woken.add(
                  new WokenResource(
                      (String) target.get("source_instance"),
                      ((Number) target.get("project_id")).longValue(),
                      (String) target.get("resource_type"),
                      ((Number) target.get("resource_id")).longValue()));
            }
          }
        }
        jdbcTemplate.update(
            "update form_directory_event_cursors set last_consumed_event_id = ?,"
                + " updated_at = clock_timestamp() where scope_id = ?",
            eventId,
            scopeId);
      }
      jdbcTemplate.update(
          "update form_directory_events set status = 'CONSUMED',"
              + " consumed_at = clock_timestamp(), lease_owner = null, lease_until = null"
              + " where id = ?",
          eventId);
      consumed++;
    }
    return new Consumption(consumed, List.copyOf(woken), unpublishedFormFactResources());
  }

  /**
   * 在范围锁内用当前源集合推进该范围全部 FORM 权威 head。
   *
   * @return 被唤醒的资源身份
   */
  private List<Map<String, Object>> wakeHeads(
      long scopeId, long versionId, long revision, String hash) {
    jdbcTemplate.update(
        """
        update form_change_heads h
           set latest_change_version = h.latest_change_version + 1,
               status = 'PENDING',
               lease_owner = null, lease_until = null, next_attempt_at = null,
               pending_storage_version_id = ?,
               pending_source_set_revision = ?,
               pending_source_set_hash = ?,
               pending_authority_revision = a.authority_revision,
               updated_at = clock_timestamp()
          from form_resource_authorities a
         where a.scope_id = ? and a.authority = 'FORM'
           and h.source_config_id = a.source_config_id
           and h.project_id = a.project_id
           and h.resource_type = a.resource_type
           and h.resource_id = a.resource_id
           and h.template_key = a.template_key
           and h.scope_id = a.scope_id
        """,
        versionId,
        revision,
        hash,
        scopeId);
    return jdbcTemplate.queryForList(
        """
        select distinct h.source_instance, h.project_id, h.resource_type, h.resource_id
          from form_change_heads h
          join form_resource_authorities a
            on a.source_config_id = h.source_config_id
           and a.project_id = h.project_id
           and a.resource_type = h.resource_type
           and a.resource_id = h.resource_id
           and a.template_key = h.template_key
           and a.scope_id = h.scope_id
         where a.scope_id = ? and a.authority = 'FORM'
         order by h.project_id, h.resource_type, h.resource_id
        """,
        scopeId);
  }

  /**
   * 未被公开的事实 head 映射回 FORM 资源，作为提交失败或重启后的持久补投依据。
   *
   * <p>只取 FORM 权威对应的议题/合并请求；多个事实族命中同一资源时去重。提交侧仍沿用既有
   * FACT_REFRESH 运行合并规则，不新增补投任务类型。
   */
  private List<WokenResource> unpublishedFormFactResources() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select distinct h.source_instance, h.project_id, a.resource_type, a.resource_id
              from fact_change_heads h
              join form_resource_authorities a
                on a.source_instance = h.source_instance
               and a.project_id = h.project_id
               and a.authority = 'FORM'
               and (
                 (h.fact_type = 'ISSUE' and a.resource_type = 'issue'
                   and a.resource_id = (
                     select i.iid from ods_gitlab_issues i
                      where i.id = h.root_id and i.project_id = h.project_id))
                 or
                 (h.fact_type = 'MERGE_REQUEST' and a.resource_type = 'merge_request'
                   and a.resource_id = (
                     select m.iid from ods_gitlab_merge_requests m
                      where m.id = h.root_id and m.target_project_id = h.project_id))
               )
             where h.latest_change_version > h.published_version
             order by h.project_id, a.resource_type, a.resource_id
            """);
    List<WokenResource> result = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      result.add(
          new WokenResource(
              (String) row.get("source_instance"),
              ((Number) row.get("project_id")).longValue(),
              (String) row.get("resource_type"),
              ((Number) row.get("resource_id")).longValue()));
    }
    return List.copyOf(result);
  }

}
