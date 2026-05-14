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
        new GitlabSystemHookPreciseSyncTarget("issues", "id", 101L),
        new GitlabSystemHookPreciseSyncTarget("issue_assignees", "issue_id", 101L),
        new GitlabSystemHookPreciseSyncTarget("issue_metrics", "issue_id", 101L),
        new GitlabSystemHookPreciseSyncTarget("label_links", "target_id", 101L));
  }

  @Test
  void shouldPlanMergeRequestSystemHookToMergeRequestsAndMergeTrains() {
    Map<String, Object> payload = Map.of(
        "object_kind", "merge_request",
        "object_attributes", Map.of("id", 202L));

    List<GitlabSystemHookPreciseSyncTarget> targets = planner.planTargets(payload);

    assertThat(targets).containsExactly(
        new GitlabSystemHookPreciseSyncTarget("merge_requests", "id", 202L),
        new GitlabSystemHookPreciseSyncTarget("merge_request_assignees", "merge_request_id", 202L),
        new GitlabSystemHookPreciseSyncTarget("merge_request_reviewers", "merge_request_id", 202L),
        new GitlabSystemHookPreciseSyncTarget("merge_request_metrics", "merge_request_id", 202L),
        new GitlabSystemHookPreciseSyncTarget("label_links", "target_id", 202L),
        new GitlabSystemHookPreciseSyncTarget("merge_trains", "merge_request_id", 202L));
  }

  @Test
  void shouldPlanNoteSystemHookToNotesAndParentIssue() {
    Map<String, Object> payload = Map.of(
        "object_kind", "note",
        "object_attributes", Map.of(
            "id", 303L,
            "noteable_type", "Issue",
            "noteable_id", 404L));

    List<GitlabSystemHookPreciseSyncTarget> targets = planner.planTargets(payload);

    assertThat(targets).containsExactly(
        new GitlabSystemHookPreciseSyncTarget("notes", "id", 303L),
        new GitlabSystemHookPreciseSyncTarget("issues", "id", 404L));
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
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("ci_pipelines", "id", 505L));
    assertThat(planner.planTargets(buildPayload))
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("ci_builds", "id", 606L));
  }

  @Test
  void shouldPlanDeploymentReleaseProjectAndUserSystemHooks() {
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
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("deployments", "id", 707L));
    assertThat(planner.planTargets(releasePayload))
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("releases", "id", 808L));
    assertThat(planner.planTargets(projectPayload))
        .containsExactly(
            new GitlabSystemHookPreciseSyncTarget("projects", "id", 909L),
            new GitlabSystemHookPreciseSyncTarget("members", "source_id", 909L),
            new GitlabSystemHookPreciseSyncTarget("environments", "project_id", 909L),
            new GitlabSystemHookPreciseSyncTarget("todos", "project_id", 909L));
    assertThat(planner.planTargets(userPayload))
        .containsExactly(new GitlabSystemHookPreciseSyncTarget("users", "id", 1001L));
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
