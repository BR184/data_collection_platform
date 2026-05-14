package com.data.collection.platform.service;

import java.util.List;

public record GitlabSystemHookPreciseSyncPlan(
    String objectKey,
    String objectId,
    List<GitlabSystemHookPreciseSyncTarget> targets) {
}
