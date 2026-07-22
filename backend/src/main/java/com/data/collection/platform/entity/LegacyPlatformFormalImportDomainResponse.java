package com.data.collection.platform.entity;

public record LegacyPlatformFormalImportDomainResponse(
    String status,
    long insertedCount,
    long updatedCount,
    long skippedCount,
    long deletedCount,
    String message) {}
