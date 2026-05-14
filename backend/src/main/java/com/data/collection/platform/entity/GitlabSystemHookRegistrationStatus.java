package com.data.collection.platform.entity;

import java.util.List;

public record GitlabSystemHookRegistrationStatus(
    boolean supported,
    boolean configured,
    boolean registered,
    Long projectId,
    String systemHookUrl,
    String message,
    List<RegisteredGitlabSystemHook> hooks) {

  public record RegisteredGitlabSystemHook(
      Long id,
      String url,
      boolean issuesEvents,
      boolean mergeRequestsEvents,
      boolean noteEvents,
      boolean pipelineEvents,
      boolean jobEvents,
      boolean releasesEvents,
      boolean enableSslVerification) {
  }
}
