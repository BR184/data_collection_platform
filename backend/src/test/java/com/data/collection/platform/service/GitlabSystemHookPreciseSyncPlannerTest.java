package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSystemHookPreciseSyncPlannerTest {

  private GitlabSystemHookPreciseSyncPlanner planner;

  @BeforeEach
  void setUp() {
    planner = new GitlabSystemHookPreciseSyncPlanner();
  }

  @Test
  void shouldPlanIssueSystemHookToIssuesTable() {
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "object_attributes", Map.of("id", 101L));

    List<GitlabSystemHookPreciseSyncTarget> targets = planner.planTargets(payload);

    assertThat(targets).containsExactly(
        new GitlabSystemHookPreciseSyncTarget("issues", Map.of("id", "101")),
        new GitlabSystemHookPreciseSyncTarget("issue_assignees", Map.of("issue_id", "101")),
        new GitlabSystemHookPreciseSyncTarget("issue_metrics", Map.of("issue_id", "101")),
        new GitlabSystemHookPreciseSyncTarget(
            "notes", Map.of("noteable_id", "101", "noteable_type", "Issue")),
        new GitlabSystemHookPreciseSyncTarget(
            "label_links", Map.of("target_id", "101", "target_type", "Issue")));
  }

  @Test
  void shouldSkipTypedLabelScopeWhenIssueIdIsMissing() {
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "object_attributes", Map.of());

    List<GitlabSystemHookPreciseSyncTarget> targets = planner.planTargets(payload);

    assertThat(targets).isEmpty();
  }

  @Test
  void test_merge_request_hook_plans_only_supported_authoritative_scopes() {
    Map<String, Object> payload = Map.of(
        "object_kind", "merge_request",
        "object_attributes", Map.of("id", 202L));

    List<GitlabSystemHookPreciseSyncTarget> targets = planner.planTargets(payload);

    assertThat(targets).containsExactly(
        new GitlabSystemHookPreciseSyncTarget("merge_requests", Map.of("id", "202")),
        new GitlabSystemHookPreciseSyncTarget("merge_request_assignees", Map.of("merge_request_id", "202")),
        new GitlabSystemHookPreciseSyncTarget("merge_request_reviewers", Map.of("merge_request_id", "202")),
        new GitlabSystemHookPreciseSyncTarget("merge_request_metrics", Map.of("merge_request_id", "202")),
        new GitlabSystemHookPreciseSyncTarget(
            "notes", Map.of("noteable_id", "202", "noteable_type", "MergeRequest")),
        new GitlabSystemHookPreciseSyncTarget(
            "label_links", Map.of("target_id", "202", "target_type", "MergeRequest")));
  }

  @Test
  void test_note_hook_uses_complete_polymorphic_parent_scope() {
    Map<String, Object> payload = Map.of(
        "object_kind", "note",
        "object_attributes", Map.of(
            "id", 303L,
            "noteable_type", "Issue",
            "noteable_id", 404L));

    List<GitlabSystemHookPreciseSyncTarget> targets = planner.planTargets(payload);

    assertThat(targets).containsExactly(
        new GitlabSystemHookPreciseSyncTarget(
            "notes", Map.of("noteable_id", "404", "noteable_type", "Issue")));
  }

  @Test
  void shouldPlanPipelineAndBuildSystemHookToCiTables() {
    Map<String, Object> pipelinePayload = Map.of(
        "object_kind", "pipeline",
        "object_attributes", Map.of("id", 505L));
    Map<String, Object> buildPayload = Map.of(
        "object_kind", "build",
        "build_id", 606L);

    assertThat(planner.planTargets(pipelinePayload))
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("ci_pipelines", Map.of("id", "505")));
    assertThat(planner.planTargets(buildPayload))
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("ci_builds", Map.of("id", "606")));
  }

  @Test
  void test_entity_hooks_plan_supported_targets_without_unregistered_tables() {
    Map<String, Object> deploymentPayload = Map.of(
        "object_kind", "deployment",
        "object_attributes", Map.of("id", 707L));
    Map<String, Object> releasePayload = Map.of(
        "object_kind", "release",
        "object_attributes", Map.of("id", 808L));
    Map<String, Object> projectPayload = Map.of(
        "object_kind", "project",
        "project", Map.of("id", 909L));
    Map<String, Object> userPayload = Map.of(
        "object_kind", "user",
        "object_attributes", Map.of("id", 1001L));

    assertThat(planner.planTargets(deploymentPayload))
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("deployments", Map.of("id", "707")));
    assertThat(planner.planTargets(releasePayload)).isEmpty();
    assertThat(planner.planTargets(projectPayload))
        .containsExactly(
            new GitlabSystemHookPreciseSyncTarget("projects", Map.of("id", "909")),
            new GitlabSystemHookPreciseSyncTarget("members", Map.of("source_id", "909")),
            new GitlabSystemHookPreciseSyncTarget("environments", Map.of("project_id", "909")),
            new GitlabSystemHookPreciseSyncTarget("todos", Map.of("project_id", "909")));
    assertThat(planner.planTargets(userPayload))
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("users", Map.of("id", "1001")));
  }

  @Test
  void shouldReturnEmptyTargetsForUnsupportedSystemHook() {
    Map<String, Object> payload = Map.of(
        "object_kind", "push",
        "project", Map.of("id", 707L));

    assertThat(planner.planTargets(payload)).isEmpty();
  }

  @Test
  void shouldBuildStableObjectKeyForIssueSystemHook() {
    Map<String, Object> payload = Map.of(
        "object_kind", "issue",
        "object_attributes", Map.of("id", 101L));

    GitlabSystemHookPreciseSyncPlan plan = planner.plan(payload);

    assertThat(plan.objectKey()).isEqualTo("issue:101");
    assertThat(plan.objectId()).isEqualTo("101");
    assertThat(plan.targets()).isNotEmpty();
  }
}
