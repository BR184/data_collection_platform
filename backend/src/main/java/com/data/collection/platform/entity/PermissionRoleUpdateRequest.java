package com.data.collection.platform.entity;

import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record PermissionRoleUpdateRequest(@NotNull Set<String> permissionCodes) {}
