package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormTemplateSpec;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 在调用方的一致性快照中，按完整来源身份读取两张表的跨版本有效记录。 */
@Service
public class FormIssueCommentContentService {
  private final JdbcTemplate jdbc;
  private final FormRecordDirectoryReader reader;
  private final FormRecordContextService contexts;
  private final FormIssueCommentRenderer renderer;

  public FormIssueCommentContentService(JdbcTemplate jdbc, FormRecordDirectoryReader reader,
      FormRecordContextService contexts, FormIssueCommentRenderer renderer) {
    this.jdbc = jdbc;
    this.reader = reader;
    this.contexts = contexts;
    this.renderer = renderer;
  }

  /** 根据响应权威行生成全量展示；缺失或冲突的目录证据向外报错，不能显示为空表单。 */
  public String render(Map<String, Object> authority, String responseUrl, String replyUrl) {
    long configId = ((Number) authority.get("source_config_id")).longValue();
    long projectId = ((Number) authority.get("project_id")).longValue();
    String resourceId = authority.get("resource_id").toString();
    List<FormIssueCommentRenderer.CommentRecord> responses = new ArrayList<>();
    List<FormIssueCommentRenderer.CommentRecord> replies = new ArrayList<>();
    Map<Long, FormTemplateSpec> specs = new HashMap<>();
    LocalDateTime changedAt = timestamp(authority.get("entry_comment_changed_at"));
    if (changedAt == null) changedAt = timestamp(authority.get("entry_registered_at"));
    var scopes = jdbc.queryForList("""
        select template_key, scope_id from form_resource_authorities
         where source_config_id = ? and source_instance = ? and project_id = ?
           and resource_type = 'issue' and resource_id = ? and authority = 'FORM'
           and template_key in ('issue_response', 'issue_reply') order by template_key
        """, configId, authority.get("source_instance"), projectId, Long.parseLong(resourceId));
    for (var scope : scopes) {
      String templateKey = (String) scope.get("template_key");
      var records = reader.listByResource(((Number) scope.get("scope_id")).longValue(), "issue", resourceId, projectId);
      for (var record : records) {
        if (record.sourceConfigId() == null || record.sourceConfigId() != configId
            || !record.sourceInstance().equals(authority.get("source_instance"))) {
          throw new IllegalStateException("评论记录来源身份不一致：" + record.recordId());
        }
        FormTemplateSpec spec = specs.computeIfAbsent(record.storageVersionId(), id -> contexts.parseSpec(
            jdbc.queryForObject("select field_spec::text from form_template_versions where id = ?", String.class, id), templateKey));
        var view = new FormIssueCommentRenderer.CommentRecord(record.recordId(), spec, record.fields());
        (FormIssueLinkService.RESPONSE_TEMPLATE.equals(templateKey) ? responses : replies).add(view);
        if (record.updatedAt() != null && (changedAt == null || record.updatedAt().isAfter(changedAt))) changedAt = record.updatedAt();
      }
    }
    return renderer.render(responseUrl, replyUrl, responses, replies, changedAt);
  }

  private static LocalDateTime timestamp(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
    return value instanceof LocalDateTime time ? time : null;
  }
}
