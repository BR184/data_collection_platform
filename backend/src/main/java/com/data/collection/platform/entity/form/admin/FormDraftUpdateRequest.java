package com.data.collection.platform.entity.form.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 草稿保存请求。
 *
 * <p>{@code expectedDraftRevision} 是 CAS 条件；结构、绑定、版本标签与迁移说明一次原子保存。
 */
public record FormDraftUpdateRequest(
    @NotNull @Positive Long expectedDraftRevision,
    @NotNull JsonNode fieldSpec,
    FormBindingRequest binding,
    @Size(max = 64) String versionLabel,
    JsonNode versionEvidence,
    JsonNode migrationSpec) {}
