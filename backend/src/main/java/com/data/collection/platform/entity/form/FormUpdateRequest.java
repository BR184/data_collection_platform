package com.data.collection.platform.entity.form;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 编辑一条本人记录；recordVersion 为页面加载到的当前版本。 */
public record FormUpdateRequest(
    String gitlabBaseUrl,
    Long projectId,
    String resourceType,
    String resourceId,
    String templateKey,
    String scopeId,
    String storageVersionId,
    Integer recordVersion,
    Map<String, JsonNode> fields) {}
