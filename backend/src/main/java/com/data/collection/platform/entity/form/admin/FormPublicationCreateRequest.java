package com.data.collection.platform.entity.form.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 发布请求。
 *
 * <p>{@code expectedCurrentVersionId} 在首次发布时为 null；同 requestKey 同指纹返回原任务。
 */
public record FormPublicationCreateRequest(
    @NotNull @Positive Long draftRevision,
    @NotNull @Positive Long previewId,
    @Positive Long expectedCurrentVersionId,
    @NotBlank @Size(max = 64) String requestKey) {}
