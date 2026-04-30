package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotBlank;

public record AuthLoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {
}
