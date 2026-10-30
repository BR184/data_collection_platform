package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 表单写入的持久权威登记与核验。
 *
 * <p>资源首次创建记录时登记 {@code FORM} 权威；之后更新/作废只接受同一权威，缺权威显式拒绝，
 * 已登记 {@code COMMENT} 的资源不得被表单写入静默切换来源。
 */
@Service
public class FormRecordAuthorityService {

  private final JdbcTemplate jdbcTemplate;

  public FormRecordAuthorityService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 在写入事务内登记或核验资源权威，返回当前权威修订。
   *
   * @param context 已解析的写入上下文
   * @param operation 写入操作（{@code CREATE}/{@code UPDATE}/{@code VOID}）
   * @param now 写入时刻
   * @return 持久权威修订
   * @throws FormConflictException 权威缺失且非首次创建、已登记评论权威或身份不一致
   */
  public long ensureForWrite(FormVersionContext context, String operation, LocalDateTime now) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select scope_id, authority, authority_revision, source_instance,
                   canonical_gitlab_base_url, project_id, resource_type, template_key
              from form_resource_authorities
             where source_config_id = ? and project_id = ? and resource_type = ?
               and resource_id = ? and template_key = ?
            """,
            context.sourceConfigId(),
            context.projectId(),
            context.resourceType(),
            context.resourceId(),
            context.spec().templateKey());
    if (rows.isEmpty()) {
      if (!"CREATE".equals(operation)) {
        throw new FormConflictException("资源尚未登记表单权威，拒绝写入；请先完成首次登记");
      }
      if (!context.writable()) {
        throw new FormConflictException("表单范围或版本非启用状态，拒绝首次登记");
      }
      jdbcTemplate.update(
          """
          insert into form_resource_authorities(
              source_config_id, source_instance, canonical_gitlab_base_url, project_id,
              resource_type, resource_id, template_key, scope_id, authority, authority_revision,
              effective_at, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, 'FORM', 1, ?, ?, ?)
          on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
          do nothing
          """,
          context.sourceConfigId(),
          context.sourceInstance(),
          context.canonicalGitlabBaseUrl(),
          context.projectId(),
          context.resourceType(),
          context.resourceId(),
          context.spec().templateKey(),
          context.scopeId(),
          Timestamp.valueOf(now),
          Timestamp.valueOf(now),
          Timestamp.valueOf(now));
      return requireRevision(context);
    }
    Map<String, Object> row = rows.getFirst();
    if (!"FORM".equals(row.get("authority"))) {
      throw new FormConflictException("资源已登记评论权威，拒绝表单写入");
    }
    if (((Number) row.get("scope_id")).longValue() != context.scopeId()
        || !context.sourceInstance().equals(row.get("source_instance"))
        || !canonical(context.canonicalGitlabBaseUrl())
            .equals(canonical((String) row.get("canonical_gitlab_base_url")))
        || ((Number) row.get("project_id")).longValue() != context.projectId()
        || !context.resourceType().equals(row.get("resource_type"))
        || !context.spec().templateKey().equals(row.get("template_key"))) {
      throw new FormConflictException("资源持久权威与写入上下文不一致");
    }
    return ((Number) row.get("authority_revision")).longValue();
  }

  private long requireRevision(FormVersionContext context) {
    Long revision =
        jdbcTemplate.queryForObject(
            """
            select authority_revision from form_resource_authorities
             where source_config_id = ? and project_id = ? and resource_type = ?
               and resource_id = ? and template_key = ?
            """,
            Long.class,
            context.sourceConfigId(),
            context.projectId(),
            context.resourceType(),
            context.resourceId(),
            context.spec().templateKey());
    if (revision == null) {
      throw new FormConflictException("表单权威登记未生效");
    }
    return revision;
  }

  private String canonical(String raw) {
    try {
      return FormSourceUrlSupport.canonicalize(raw);
    } catch (IllegalArgumentException error) {
      throw new FormConflictException("表单来源地址非法：" + raw);
    }
  }
}
