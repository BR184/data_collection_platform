package com.data.collection.platform.entity.external;

public record ExternalDatasetParameter(
    String name,
    String type,
    boolean required,
    String description,
    String example) {
}
