package com.data.collection.platform.entity;

public record PermissionCatalogItemResponse(
    String permissionCode,
    String permissionName,
    String moduleName,
    String description,
    int sortOrder) {}
