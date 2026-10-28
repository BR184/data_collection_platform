package com.data.collection.platform.entity.form;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 一条记录的权威读取结果；字段键为冻结规格的 apiKey。 */
public record FormRecordView(
    String id,
    int recordVersion,
    boolean deleted,
    boolean legacyIncomplete,
    List<String> legacyMissingFields,
    Map<String, Object> fields,
    Map<String, String> fieldNames,
    Map<String, Boolean> snapshotValues,
    String createdBy,
    String createdByName,
    String templateVersion,
    String originVersionId,
    String storageVersionId,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    boolean canEdit,
    boolean canVoid) {}
