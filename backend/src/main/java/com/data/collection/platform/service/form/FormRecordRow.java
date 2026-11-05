package com.data.collection.platform.service.form;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 版本记录表中的一条权威行；业务值按 apiKey 读取。 */
public record FormRecordRow(
    long id,
    long instanceId,
    long originVersionId,
    String templateVersion,
    String createdBy,
    String requestKey,
    String createPayloadHash,
    int recordVersion,
    boolean deleted,
    boolean legacyIncomplete,
    List<String> legacyMissingFields,
    Map<String, Object> fields,
    Map<String, String> fieldNames,
    Map<String, Boolean> snapshotValues,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
