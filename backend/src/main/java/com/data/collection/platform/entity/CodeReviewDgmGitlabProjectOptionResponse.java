package com.data.collection.platform.entity;

public record CodeReviewDgmGitlabProjectOptionResponse(
    Long gitlabProjectId,
    String name,
    String path,
    String pathWithNamespace,
    String webUrl,
    String namespaceName,
    String namespaceFullPath,
    boolean archived,
    String visibility,
    boolean active) {
}
