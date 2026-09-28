package com.data.collection.platform.bi.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 浏览器生成完整数据 PNG 前提交的授权身份。 */
public record BiDownloadAuthorizeRequest(
    @NotNull @Valid BiDownloadScopeRequest scope,
    @NotBlank String pageKey,
    @NotBlank String chartInstanceId,
    @NotBlank String chartTemplateId,
    @NotBlank String sourceVersion) {}
