package com.data.collection.platform.service;

import java.time.LocalDateTime;

public record SegmentMemberAuditEntry(
    long segmentId, String action, String reason, String operator, LocalDateTime createdAt) {}
