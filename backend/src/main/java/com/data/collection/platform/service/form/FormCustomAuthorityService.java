package com.data.collection.platform.service.form;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 自定义表单没有历史评论消费者；首次发布与采集终态按真实资源登记独立FORM权威。 */
@Service
public class FormCustomAuthorityService {
  private final JdbcTemplate jdbc;

  public FormCustomAuthorityService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 在既有同步终态补齐该来源的新资源；重入只插入缺失权威，不切换已存在身份。 */
  @Transactional
  public void registerConfig(long configId) {
    List<Long> scopes = jdbc.queryForList("""
        select s.id from form_template_scopes s
        join form_template_definitions d on d.template_key = s.template_key
        where s.source_config_id = ? and s.status = 'ACTIVE' and d.definition_kind = 'CUSTOM'
        order by s.id
        """, Long.class, configId);
    for (Long scope : scopes) {
      registerScope(scope);
    }
  }

  /** 范围→权威→head；在目录事件事务内登记。返回是否属于独立保存的自定义表单。 */
  @Transactional
  public boolean registerScope(long scopeId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select s.*, d.definition_kind from form_template_scopes s
        join form_template_definitions d on d.template_key = s.template_key
        where s.id = ? for share of s
        """, scopeId);
    if (rows.isEmpty() || !"CUSTOM".equals(rows.getFirst().get("definition_kind"))) {
      return false;
    }
    Map<String, Object> scope = rows.getFirst();
    if (!"ACTIVE".equals(scope.get("status")) || scope.get("current_version_id") == null) {
      return true;
    }
    // ODS属于已配置的镜像来源；其他来源不能借用同号的默认镜像资源。
    if (!"default".equals(scope.get("source_instance"))) {
      return true;
    }
    boolean mr = "merge_request".equals(scope.get("resource_type"));
    String table = mr ? "ods_gitlab_merge_requests" : "ods_gitlab_issues";
    String project = mr ? "target_project_id" : "project_id";
    if (jdbc.queryForObject("select to_regclass(?)::text", String.class, table) == null) {
      return true;
    }
    jdbc.update("""
        insert into form_resource_authorities(source_config_id, source_instance,
          canonical_gitlab_base_url, project_id, resource_type, resource_id, template_key,
          scope_id, authority, authority_revision, effective_at, created_at, updated_at)
        select s.source_config_id, s.source_instance, s.canonical_gitlab_base_url,
          r.%s, s.resource_type, r.iid, s.template_key, s.id, 'FORM', 1,
          clock_timestamp(), clock_timestamp(), clock_timestamp()
        from form_template_scopes s join %s r on s.project_ids @> jsonb_build_array(r.%s)
        where s.id = ? and not coalesce(r.mirror_deleted, false)
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key) do nothing
        """.formatted(project, table, project), scopeId);
    jdbc.update("""
        insert into form_change_heads(source_config_id, source_instance, canonical_gitlab_base_url,
          project_id, resource_type, resource_id, template_key, scope_id,
          pending_storage_version_id, pending_authority_revision,
          pending_source_set_revision, pending_source_set_hash, status, created_at, updated_at)
        select a.source_config_id, a.source_instance, a.canonical_gitlab_base_url,
          a.project_id, a.resource_type, a.resource_id, a.template_key, a.scope_id,
          s.current_version_id, a.authority_revision, s.source_set_revision, s.source_set_hash,
          'CONSUMED', clock_timestamp(), clock_timestamp()
        from form_resource_authorities a join form_template_scopes s on s.id = a.scope_id
        where a.scope_id = ? and a.authority = 'FORM'
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key) do nothing
        """, scopeId);
    return true;
  }
}
