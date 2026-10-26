package com.data.collection.platform.domain.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 范围绑定规范化 JSON 与哈希；迁移、启动校验必须共用同一序列化顺序。 */
public final class FormScopeBinding {

  private FormScopeBinding() {}

  public static ObjectNode create(
      ObjectMapper objectMapper,
      String templateKey,
      long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds,
      long versionProjectId,
      String resourceType) {
    ObjectNode binding = objectMapper.createObjectNode();
    binding.put("templateKey", templateKey);
    binding.put("sourceConfigId", sourceConfigId);
    binding.put("sourceInstance", sourceInstance);
    binding.put("canonicalGitlabBaseUrl", canonicalGitlabBaseUrl);
    ArrayNode projects = binding.putArray("projectIds");
    projectIds.forEach(projects::add);
    binding.put("versionProjectId", versionProjectId);
    binding.put("resourceType", resourceType);
    return binding;
  }

  public static String hash(ObjectMapper objectMapper, ObjectNode binding) {
    try {
      return FormHashSupport.sha256Hex(objectMapper.writeValueAsString(binding));
    } catch (Exception error) {
      throw new IllegalStateException("Failed to serialize form scope binding", error);
    }
  }
}
