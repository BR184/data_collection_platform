package com.data.collection.platform.entity.form.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 按指定草稿修订生成预览；证据按范围与修订唯一。 */
public record FormPreviewRequest(@NotNull @Positive Long draftRevision) {}
