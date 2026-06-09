package com.data.collection.platform.service;

public record SegmentMember(
    String entityType,
    String entityId,
    String displayName,
    String memberPayloadJson,
    SegmentMemberSource memberSource) {}
