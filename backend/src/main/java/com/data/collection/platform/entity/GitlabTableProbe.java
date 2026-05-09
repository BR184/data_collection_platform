package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record GitlabTableProbe(
    long rowCount,
    LocalDateTime maxUpdatedAt,
    String minPrimaryKey,
    String maxPrimaryKey) {
}
