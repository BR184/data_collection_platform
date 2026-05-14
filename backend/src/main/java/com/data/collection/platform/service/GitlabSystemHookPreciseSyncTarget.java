package com.data.collection.platform.service;

public record GitlabSystemHookPreciseSyncTarget(
    String tableName,
    String lookupColumn,
    Object lookupValue) {
}
