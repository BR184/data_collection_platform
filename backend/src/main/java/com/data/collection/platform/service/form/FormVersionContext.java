package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次记录的完整结构上下文。
 *
 * <p>表名只来自服务端元数据并再次校验；业务值不参与表名或列名拼接。
 */
public record FormVersionContext(
    long scopeId,
    String scopeStatus,
    long sourceConfigId,
    String sourceInstance,
    String canonicalGitlabBaseUrl,
    long storageVersionId,
    String versionLabel,
    String versionStatus,
    String instanceTable,
    String recordTable,
    FormTemplateSpec spec,
    JsonNode fieldSpecJson,
    long projectId,
    String resourceType,
    long resourceId,
    Long rootId,
    String severityLevel,
    String webUrl) {

  public boolean writable() {
    return "ACTIVE".equals(scopeStatus) && "PUBLISHED".equals(versionStatus);
  }
}
