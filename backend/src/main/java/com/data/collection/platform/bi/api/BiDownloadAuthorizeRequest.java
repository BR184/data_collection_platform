package com.data.collection.platform.bi.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** 浏览器生成完整数据 PNG 前提交的授权身份。 */
public record BiDownloadAuthorizeRequest(
    @Positive long productVersionId,
    @NotBlank String pageKey,
    @NotBlank String chartTemplateId,
    @NotBlank String sourceVersion) {}
