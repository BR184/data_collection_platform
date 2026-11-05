package com.data.collection.platform.entity.form.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新建 DRAFT 表单范围；初始草稿取自模板种子结构。 */
public record FormScopeCreateRequest(
    @NotBlank @Size(max = 64) String templateKey,
    @NotBlank
        @Size(max = 64)
        @Pattern(
            regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$",
            message = "范围键只能包含字母、数字、下划线和连字符")
        String scopeKey) {}
