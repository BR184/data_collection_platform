package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record IssueScopeOrderRequest(@NotEmpty List<@NotNull Long> ids) {}

