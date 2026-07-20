package com.data.collection.platform.entity.external;

import java.time.OffsetDateTime;

public record ExternalDatasetResponse<T>(
    String datasetKey,
    String schemaVersion,
    OffsetDateTime generatedAt,
    T payload) {
}
