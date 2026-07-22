package com.data.collection.platform.entity;

public record CodeReviewMatchModeStatusResponse(
    boolean enabled,
    String codeReviewReadMode,
    boolean codeReviewCompatibilityRead) {
}
