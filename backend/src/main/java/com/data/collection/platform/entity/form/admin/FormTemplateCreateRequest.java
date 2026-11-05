package com.data.collection.platform.entity.form.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 创建独立表单；复制只取冻结字段配置，名称和内部身份独立。 */
public record FormTemplateCreateRequest(
    @NotBlank @Size(max = 128) String displayName,
    @NotBlank @Pattern(regexp = "issue|merge_request") String resourceType,
    @NotBlank @Pattern(regexp = "AUTHORIZED_USER|RESOURCE_AUTHOR") String fillPolicy,
    Long copyFromVersionId) {}
