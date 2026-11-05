package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 表单写入的持久权威核验。
 *
 * <p>写入只接受已登记的 {@code FORM} 权威：缺失时由同步侧首次判定流程登记，填写动作不能自行
 * 创建或切换权威。已登记 {@code COMMENT} 的资源拒绝表单写入，来源身份不一致同样拒绝。
 */
@Service
public class FormRecordAuthorityService {

  private final JdbcTemplate jdbcTemplate;

  public FormRecordAuthorityService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 核验资源已有 {@code FORM} 权威与写入上下文一致，返回当前权威修订。
   *
   * @param context 已解析的写入上下文
   * @param operation 写入操作（{@code CREATE}/{@code UPDATE}/{@code VOID}）
   * @return 持久权威修订
   * @throws FormConflictException 权威缺失、已登记评论权威或身份不一致
   */
  public long ensureForWrite(FormVersionContext context, String operation) {
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
      throw new FormConflictException("资源尚未完成持久表单权威登记，拒绝写入；请等待同步登记后重试");
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
    if ("CREATE".equals(operation) && !context.writable()) {
      throw new FormConflictException("表单范围或版本非启用状态，拒绝新建");
    }
    return ((Number) row.get("authority_revision")).longValue();
  }

  /** 详情能力复用写入口的持久权威核验，尚未登记时只读且不产生登记副作用。 */
  public boolean isReady(FormVersionContext context) {
    try {
      ensureForWrite(context, "READ");
      return true;
    } catch (FormConflictException error) {
      return false;
    }
  }

  private String canonical(String raw) {
    try {
      return FormSourceUrlSupport.canonicalize(raw);
    } catch (IllegalArgumentException error) {
      throw new FormConflictException("表单来源地址非法：" + raw);
    }
  }
}
