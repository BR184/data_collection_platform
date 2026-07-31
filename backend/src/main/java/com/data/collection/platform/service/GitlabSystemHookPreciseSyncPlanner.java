package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
// System Hook 精确同步规划器把 GitLab 事件转换成需要回读的镜像表和主键条件。
// 无法定位精确目标时返回空计划，由外层决定是否退回补偿同步。
public class GitlabSystemHookPreciseSyncPlanner {
  public GitlabSystemHookPreciseSyncPlan plan(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return new GitlabSystemHookPreciseSyncPlan("system_hook:unknown", "unknown", List.of());
    }
    String objectKind = asString(payload.get("object_kind")).toLowerCase();
    Map<String, Object> attributes = asMap(payload.get("object_attributes"));
    String objectId = resolveObjectId(objectKind, payload, attributes);
    return new GitlabSystemHookPreciseSyncPlan(objectKind + ":" + objectId, objectId, planTargets(payload));
  }

  public List<GitlabSystemHookPreciseSyncTarget> planTargets(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return List.of();
    }
    String objectKind = asString(payload.get("object_kind")).toLowerCase();
    Map<String, Object> attributes = asMap(payload.get("object_attributes"));
    Set<GitlabSystemHookPreciseSyncTarget> targets = new LinkedHashSet<>();
    switch (objectKind) {
      case "issue" -> {
        Object issueId = attributes.get("id");
        addIfPresent(targets, "issues", "id", issueId);
        addIfPresent(targets, "issue_assignees", "issue_id", issueId);
        addIfPresent(targets, "issue_metrics", "issue_id", issueId);
        addNoteableTarget(targets, issueId, "Issue");
        addTypedLabelTarget(targets, issueId, "Issue");
      }
      case "merge_request" -> {
        Object mergeRequestId = attributes.get("id");
        addIfPresent(targets, "merge_requests", "id", mergeRequestId);
        addIfPresent(targets, "merge_request_assignees", "merge_request_id", mergeRequestId);
        addIfPresent(targets, "merge_request_reviewers", "merge_request_id", mergeRequestId);
        addIfPresent(targets, "merge_request_metrics", "merge_request_id", mergeRequestId);
        addNoteableTarget(targets, mergeRequestId, "MergeRequest");
        addTypedLabelTarget(targets, mergeRequestId, "MergeRequest");
      }
      case "note" -> {
        String noteableType = asString(attributes.get("noteable_type"));
        Object noteableId = attributes.get("noteable_id");
        if ("Issue".equalsIgnoreCase(noteableType)) {
          addNoteableTarget(targets, noteableId, "Issue");
        } else if ("MergeRequest".equalsIgnoreCase(noteableType)) {
          addNoteableTarget(targets, noteableId, "MergeRequest");
        }
      }
      case "pipeline" -> addIfPresent(targets, "ci_pipelines", "id", attributes.get("id"));
      case "build", "job" -> {
        Object buildId = payload.getOrDefault("build_id", attributes.get("id"));
        addIfPresent(targets, "ci_builds", "id", buildId);
      }
      case "deployment" -> addIfPresent(targets, "deployments", "id", attributes.get("id"));
      case "project" -> {
        Map<String, Object> project = asMap(payload.get("project"));
        Object projectId = project.getOrDefault("id", attributes.get("id"));
        addIfPresent(targets, "projects", "id", projectId);
        addIfPresent(targets, "members", "source_id", projectId);
        addIfPresent(targets, "environments", "project_id", projectId);
        addIfPresent(targets, "todos", "project_id", projectId);
      }
      case "user" -> addIfPresent(targets, "users", "id", attributes.get("id"));
      default -> {
        return List.of();
      }
    }
    return new ArrayList<>(targets);
  }

  private void addIfPresent(Set<GitlabSystemHookPreciseSyncTarget> targets, String tableName, String lookupColumn, Object lookupValue) {
    if (lookupColumn == null || lookupColumn.isBlank() || lookupValue == null) {
      return;
    }
    Map<String, Object> lookupScope = new java.util.LinkedHashMap<>();
    lookupScope.put(lookupColumn, lookupValue);
    addIfPresent(targets, tableName, lookupScope);
  }

  private void addIfPresent(
      Set<GitlabSystemHookPreciseSyncTarget> targets,
      String tableName,
      Map<String, Object> lookupScope) {
    if (lookupScope == null || lookupScope.isEmpty()) {
      return;
    }
    Map<String, String> normalizedScope = new java.util.TreeMap<>();
    for (Map.Entry<String, Object> entry : lookupScope.entrySet()) {
      Object value = entry.getValue();
      if (entry.getKey() == null
          || entry.getKey().isBlank()
          || value == null
          || (value instanceof String stringValue && stringValue.isBlank())) {
        return;
      }
      normalizedScope.put(entry.getKey(), String.valueOf(value));
    }
    targets.add(new GitlabSystemHookPreciseSyncTarget(tableName, normalizedScope));
  }

  private void addTypedLabelTarget(
      Set<GitlabSystemHookPreciseSyncTarget> targets, Object targetId, String targetType) {
    if (targetId == null) {
      return;
    }
    addIfPresent(targets, "label_links", Map.of("target_id", targetId, "target_type", targetType));
  }

  private void addNoteableTarget(
      Set<GitlabSystemHookPreciseSyncTarget> targets, Object noteableId, String noteableType) {
    if (noteableId == null) {
      return;
    }
    addIfPresent(
        targets,
        "notes",
        Map.of("noteable_id", noteableId, "noteable_type", noteableType));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private String asString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String resolveObjectId(String objectKind, Map<String, Object> payload, Map<String, Object> attributes) {
    Object candidate = switch (objectKind) {
      case "issue", "merge_request", "note", "pipeline", "deployment", "release", "user" -> attributes.get("id");
      case "build", "job" -> payload.getOrDefault("build_id", attributes.get("id"));
      case "project" -> asMap(payload.get("project")).getOrDefault("id", attributes.get("id"));
      default -> attributes.getOrDefault("id", payload.get("project_id"));
    };
    return candidate == null ? "unknown" : String.valueOf(candidate);
  }
}
