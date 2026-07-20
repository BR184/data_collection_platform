package com.data.collection.platform.entity.external;

import java.time.OffsetDateTime;
import java.util.List;

public record ExternalDatasetCatalogResponse(
    String apiVersion,
    OffsetDateTime generatedAt,
    List<ExternalDatasetDescriptor> datasets) {
  public ExternalDatasetCatalogResponse {
    datasets = datasets == null ? List.of() : List.copyOf(datasets);
  }
}
