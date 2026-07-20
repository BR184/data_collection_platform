package com.data.collection.platform.entity;

import java.util.Set;

public record PermissionRoleResponse(
    String roleCode,
    String roleName,
    Integer displayOrder,
    Set<String> permissionCodes) {}
