package com.data.collection.platform.entity.form;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 新增一条表单记录的协议；记录 ID 与结构 ID 使用十进制字符串。 */
public record FormCreateRequest(
    String gitlabBaseUrl,
    Long projectId,
    String resourceType,
    String resourceId,
    String templateKey,
    String scopeId,
    String storageVersionId,
    String requestKey,
    boolean confirmSameReviewer,
    Map<String, JsonNode> fields) {}
