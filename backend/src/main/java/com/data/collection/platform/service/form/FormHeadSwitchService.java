package com.data.collection.platform.service.form;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 结构切换的变更 head 版本保护。
 *
 * <p>受影响集合定义为 A（本范围本模板全部 FORM 权威资源）∪ B（目标版本实例）；切换前补建缺失
 * head、按 id 升序锁定并推进待处理结构版本，提交前三项不变量必须全部为 0。
 */
@Service
public class FormHeadSwitchService {

  private final JdbcTemplate jdbcTemplate;

  public FormHeadSwitchService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 在切换事务内登记权威与 head 并推进结构版本；调用方必须已持有范围行锁。
   *
   * @param scopeId 范围 ID
   * @param templateKey 模板键
   * @param toVersionId 目标结构版本 ID
   * @param instanceTables 目标及仍可读旧版的实例表（已核验表名，目标在前）
   * @param now 提交时间
   */
  public void activate(
      long scopeId,
      String templateKey,
      long toVersionId,
      List<String> instanceTables,
      LocalDateTime now) {
    String union = instanceUnion(instanceTables);
    insertMissingAuthorities(scopeId, union, now);
    requireNoConflictingAuthorities(scopeId, union);
    insertMissingHeads(scopeId, templateKey, toVersionId, union, now);
    List<Long> affected = affectedHeadIds(scopeId, templateKey, union);
    lockHeads(affected);
    updateHeads(affected, toVersionId, now);
    verifyInvariants(scopeId, templateKey, toVersionId, union);
  }

  private String instanceUnion(List<String> instanceTables) {
    return instanceTables.stream()
        .map(
            table ->
                "select source_config_id, source_instance, gitlab_base_url, project_id,"
                    + " resource_type, resource_id, template_key, scope_id from \"%s\""
                        .formatted(table))
        .collect(Collectors.joining(" union all ", "(", ")"));
  }

  private void insertMissingAuthorities(
      long scopeId, String instanceUnion, LocalDateTime now) {
    jdbcTemplate.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id, resource_type,
            resource_id, template_key, scope_id, authority, authority_revision, effective_at,
            created_at, updated_at)
        select i.source_config_id, i.source_instance, i.gitlab_base_url, i.project_id,
               i.resource_type, i.resource_id, i.template_key, i.scope_id, 'FORM', 1, ?, ?, ?
          from %s i
         where not exists (
           select 1 from form_resource_authorities a
            where a.source_config_id = i.source_config_id and a.project_id = i.project_id
              and a.resource_type = i.resource_type and a.resource_id = i.resource_id
              and a.template_key = i.template_key)
        """
            .formatted(instanceUnion),
        java.sql.Timestamp.valueOf(now),
        java.sql.Timestamp.valueOf(now),
        java.sql.Timestamp.valueOf(now));
  }

  private void requireNoConflictingAuthorities(long scopeId, String instanceUnion) {
    Integer conflicts =
        jdbcTemplate.queryForObject(
            """
            select count(*) from %s i
              join form_resource_authorities a
                on a.source_config_id = i.source_config_id and a.project_id = i.project_id
               and a.resource_type = i.resource_type and a.resource_id = i.resource_id
               and a.template_key = i.template_key
             where a.scope_id <> ? or a.authority <> 'FORM'
            """
                .formatted(instanceUnion),
            Integer.class,
            scopeId);
    if (conflicts != null && conflicts > 0) {
      throw new IllegalStateException(
          "目标实例存在非本范围 FORM 权威，停止切换；冲突数=" + conflicts);
    }
  }

  private void insertMissingHeads(
      long scopeId,
      String templateKey,
      long toVersionId,
      String instanceUnion,
      LocalDateTime now) {
    jdbcTemplate.update(
        """
        insert into form_change_heads(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id, resource_type,
            resource_id, template_key, scope_id, latest_change_version, consumed_change_version,
            pending_storage_version_id, status, fencing_version, created_at, updated_at)
        select r.source_config_id, r.source_instance, r.canonical_gitlab_base_url, r.project_id,
               r.resource_type, r.resource_id, r.template_key, r.scope_id, 1, 0, ?,
               'PENDING', 0, ?, ?
          from (
            select a.source_config_id, a.source_instance, a.canonical_gitlab_base_url,
                   a.project_id, a.resource_type, a.resource_id, a.template_key, a.scope_id
              from form_resource_authorities a
             where a.scope_id = ? and a.template_key = ? and a.authority = 'FORM'
            union
            select i.source_config_id, i.source_instance, i.gitlab_base_url, i.project_id,
                   i.resource_type, i.resource_id, i.template_key, i.scope_id
              from %s i
          ) r
         where not exists (
           select 1 from form_change_heads h
            where h.source_config_id = r.source_config_id and h.project_id = r.project_id
              and h.resource_type = r.resource_type and h.resource_id = r.resource_id
              and h.template_key = r.template_key)
        """
            .formatted(instanceUnion),
        toVersionId,
        java.sql.Timestamp.valueOf(now),
        java.sql.Timestamp.valueOf(now),
        scopeId,
        templateKey);
  }

  private List<Long> affectedHeadIds(long scopeId, String templateKey, String instanceUnion) {
    return jdbcTemplate.queryForList(
        """
        select h.id from form_change_heads h
         where h.scope_id = ? and h.template_key = ?
           and (
             exists (select 1 from %s i
                      where i.project_id = h.project_id and i.resource_type = h.resource_type
                        and i.resource_id = h.resource_id)
             or exists (select 1 from form_resource_authorities a
                         where a.scope_id = h.scope_id and a.template_key = h.template_key
                           and a.authority = 'FORM' and a.project_id = h.project_id
                           and a.resource_type = h.resource_type and a.resource_id = h.resource_id))
         order by h.id
        """
            .formatted(instanceUnion),
        Long.class,
        scopeId,
        templateKey);
  }

  private void lockHeads(List<Long> affected) {
    if (affected.isEmpty()) {
      return;
    }
    List<Long> locked =
        jdbcTemplate.query(
            con -> {
              PreparedStatement statement =
                  con.prepareStatement(
                      "select id from form_change_heads where id = any(?) order by id for update");
              statement.setArray(1, con.createArrayOf("bigint", affected.toArray(Long[]::new)));
              return statement;
            },
            (rs, rowNum) -> rs.getLong("id"));
    if (locked.size() != affected.size()) {
      throw new IllegalStateException("变更 head 锁定数量不一致，停止切换");
    }
  }

  private void updateHeads(List<Long> affected, long toVersionId, LocalDateTime now) {
    if (affected.isEmpty()) {
      return;
    }
    int updated =
        jdbcTemplate.update(
            con -> {
              PreparedStatement statement =
                  con.prepareStatement(
                      "update form_change_heads"
                          + " set pending_storage_version_id = ?,"
                          + " latest_change_version = latest_change_version + 1, updated_at = ?"
                          + " where id = any(?)");
              statement.setLong(1, toVersionId);
              statement.setTimestamp(2, java.sql.Timestamp.valueOf(now));
              statement.setArray(
                  3, con.createArrayOf("bigint", affected.toArray(Long[]::new)));
              return statement;
            });
    if (updated != affected.size()) {
      throw new IllegalStateException("变更 head 推进数量不一致，停止切换");
    }
  }

  private void verifyInvariants(
      long scopeId, String templateKey, long toVersionId, String instanceUnion) {
    Integer authorityMissing =
        jdbcTemplate.queryForObject(
            """
            select count(*) from form_resource_authorities a
              left join form_change_heads h
                on h.source_config_id = a.source_config_id and h.project_id = a.project_id
               and h.resource_type = a.resource_type and h.resource_id = a.resource_id
               and h.template_key = a.template_key
             where a.scope_id = ? and a.template_key = ? and a.authority = 'FORM'
               and (h.id is null or h.pending_storage_version_id is distinct from ?)
            """,
            Integer.class,
            scopeId,
            templateKey,
            toVersionId);
    if (authorityMissing != null && authorityMissing > 0) {
      throw new IllegalStateException("FORM 权威缺少目标版本 head，停止切换；数量=" + authorityMissing);
    }
    Integer instanceMissing =
        jdbcTemplate.queryForObject(
            """
            select count(*) from %s i
              left join form_change_heads h
                on h.source_config_id = i.source_config_id and h.project_id = i.project_id
               and h.resource_type = i.resource_type and h.resource_id = i.resource_id
               and h.template_key = i.template_key
              left join form_resource_authorities a
                on a.source_config_id = i.source_config_id and a.project_id = i.project_id
               and a.resource_type = i.resource_type and a.resource_id = i.resource_id
               and a.template_key = i.template_key
             where h.id is null or h.pending_storage_version_id is distinct from ?
                or a.id is null or a.authority <> 'FORM' or a.scope_id <> i.scope_id
            """
                .formatted(instanceUnion),
            Integer.class,
            toVersionId);
    if (instanceMissing != null && instanceMissing > 0) {
      throw new IllegalStateException("目标实例缺少 FORM head，停止切换；数量=" + instanceMissing);
    }
    Integer uncoveredEvidence =
        jdbcTemplate.queryForObject(
            """
            select count(*) from form_change_heads h
             where h.scope_id = ? and h.template_key = ?
               and h.pending_storage_version_id is not null
               and not exists (select 1 from %s i
                                where i.project_id = h.project_id
                                  and i.resource_type = h.resource_type
                                  and i.resource_id = h.resource_id)
               and not exists (select 1 from form_resource_authorities a
                                where a.scope_id = h.scope_id and a.template_key = h.template_key
                                  and a.authority = 'FORM' and a.project_id = h.project_id
                                  and a.resource_type = h.resource_type
                                  and a.resource_id = h.resource_id)
            """
                .formatted(instanceUnion),
            Integer.class,
            scopeId,
            templateKey);
    if (uncoveredEvidence != null && uncoveredEvidence > 0) {
      throw new IllegalStateException("存在未覆盖的 FORM 存储证据 head，停止切换；数量=" + uncoveredEvidence);
    }
    Integer commentEvidence =
        jdbcTemplate.queryForObject(
            """
            select count(*) from form_change_heads h
              join form_resource_authorities a
                on a.source_config_id = h.source_config_id and a.project_id = h.project_id
               and a.resource_type = h.resource_type and a.resource_id = h.resource_id
               and a.template_key = h.template_key
             where a.scope_id = ? and a.template_key = ? and a.authority = 'COMMENT'
               and h.pending_storage_version_id is not null
            """,
            Integer.class,
            scopeId,
            templateKey);
    if (commentEvidence != null && commentEvidence > 0) {
      throw new IllegalStateException("COMMENT 权威存在存储证据，停止切换；数量=" + commentEvidence);
    }
  }
}
