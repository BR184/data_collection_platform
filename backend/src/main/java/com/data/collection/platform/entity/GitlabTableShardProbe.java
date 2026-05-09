package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record GitlabTableShardProbe(
    String shardKey,
    long rowCount,
    LocalDateTime maxUpdatedAt,
    String minPrimaryKey,
    String maxPrimaryKey,
    String checksum) {
}
