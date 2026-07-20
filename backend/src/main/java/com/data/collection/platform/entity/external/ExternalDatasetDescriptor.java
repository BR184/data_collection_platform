package com.data.collection.platform.entity.external;

import java.util.List;

public record ExternalDatasetDescriptor(
    String datasetKey,
    String name,
    String description,
    String schemaVersion,
    List<ExternalDatasetParameter> parameters,
    List<ExternalDatasetField> fields) {
  public ExternalDatasetDescriptor {
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
    fields = fields == null ? List.of() : List.copyOf(fields);
  }
}
