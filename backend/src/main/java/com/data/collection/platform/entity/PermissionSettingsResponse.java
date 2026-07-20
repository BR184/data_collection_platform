package com.data.collection.platform.entity;

import java.util.List;

public record PermissionSettingsResponse(
    List<PermissionCatalogItemResponse> permissions,
    List<PermissionRoleResponse> roles,
    boolean initialLdapSyncCompleted) {}
