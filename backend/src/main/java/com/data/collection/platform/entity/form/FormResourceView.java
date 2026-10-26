package com.data.collection.platform.entity.form;

/** 表单对应的 GitLab 资源上下文；rootId 在资源尚未镜像时为空。 */
public record FormResourceView(
    String gitlabBaseUrl,
    String projectId,
    String resourceType,
    String resourceId,
    String rootId,
    String webUrl,
    String severityLevel) {}
