package com.data.collection.platform.entity.form;

/** 作废一条本人记录；必须同时提交版本、确认标记与确认文本。 */
public record FormVoidRequest(
    String gitlabBaseUrl,
    Long projectId,
    String resourceType,
    String resourceId,
    String templateKey,
    String scopeId,
    String storageVersionId,
    Integer recordVersion,
    boolean confirmVoid,
    String confirmationText) {}
