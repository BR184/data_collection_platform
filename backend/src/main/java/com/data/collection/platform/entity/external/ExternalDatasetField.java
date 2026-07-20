package com.data.collection.platform.entity.external;

public record ExternalDatasetField(
    String path,
    String type,
    boolean nullable,
    String description) {
}
