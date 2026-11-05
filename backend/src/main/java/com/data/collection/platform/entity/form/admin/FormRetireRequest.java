package com.data.collection.platform.entity.form.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 退役范围；必须与当前指针一致，历史记录继续可读。 */
public record FormRetireRequest(@NotNull @Positive Long expectedCurrentVersionId) {}
