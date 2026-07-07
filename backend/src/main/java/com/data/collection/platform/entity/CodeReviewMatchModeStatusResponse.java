package com.data.collection.platform.entity;

public record CodeReviewMatchModeStatusResponse(
    boolean enabled,
    String reviewDataReadMode,
    String codeReviewReadMode,
    boolean reviewDataCompatibilityRead,
    boolean codeReviewCompatibilityRead) {
}
