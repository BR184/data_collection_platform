package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.CollectFormEditContext;
import com.data.collection.platform.entity.CollectFormRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CollectFormAuditService {
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public CollectFormAuditService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  public void record(String action, CollectFormRecord record, CollectFormEditContext context) {
    if (record == null) {
      return;
    }
    try {
      jdbcTemplate.update(
          """
              insert into collect_form_record_audit_logs
                (record_id, action, editor_id, editor_username, reviewer, remote_address, user_agent, snapshot_json)
              values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
              """,
          record.getId(),
          action,
          clean(context == null ? null : context.editorId()),
          clean(context == null ? null : context.resolvedEditorUsername(record.getReviewer())),
          clean(record.getReviewer()),
          clean(context == null ? null : context.remoteAddress()),
          clean(context == null ? null : context.userAgent()),
          jsonUtils.toJson(snapshot(record)));
    } catch (Exception ex) {
      log.warn("Failed to write collect form audit log, action={}, recordId={}", action, record.getId(), ex);
    }
  }

  private Map<String, Object> snapshot(CollectFormRecord record) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", record.getId());
    snapshot.put("gitlabBaseUrl", record.getGitlabBaseUrl());
    snapshot.put("projectId", record.getProjectId());
    snapshot.put("requestIid", record.getRequestIid());
    snapshot.put("resourceType", record.getResourceType());
    snapshot.put("resourceId", record.getResourceId());
    snapshot.put("templateCode", record.getTemplateCode());
    snapshot.put("formTitle", record.getFormTitle());
    snapshot.put("reviewer", record.getReviewer());
    snapshot.put("reviewDurationMinutes", record.getReviewDurationMinutes());
    snapshot.put("specificationScore", record.getSpecificationScore());
    snapshot.put("logicScore", record.getLogicScore());
    snapshot.put("performanceScore", record.getPerformanceScore());
    snapshot.put("designScore", record.getDesignScore());
    snapshot.put("otherScore", record.getOtherScore());
    snapshot.put("remark", record.getRemark());
    snapshot.put("deleted", record.isDeleted());
    snapshot.put("createdAt", record.getCreatedAt());
    snapshot.put("updatedAt", record.getUpdatedAt());
    return snapshot;
  }

  private String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
