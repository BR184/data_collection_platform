package com.data.collection.platform.entity.form;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 填写页完整读取结果：冻结规格、结构版本、资源、账号与全部未作废记录。 */
public record FormDetailResponse(
    String templateKey,
    JsonNode fieldSpec,
    String scopeId,
    String storageVersionId,
    String versionLabel,
    String scopeStatus,
    boolean writable,
    String instanceId,
    List<FormRecordView> records,
    FormResourceView resource,
    FormAccountView account,
    FormCapabilitiesView capabilities) {}
