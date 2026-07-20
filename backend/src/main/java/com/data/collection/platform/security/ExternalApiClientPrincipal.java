package com.data.collection.platform.security;

import java.util.Set;

public record ExternalApiClientPrincipal(String clientId, Set<String> allowedDatasets) {
  public boolean canRead(String datasetKey) {
    return allowedDatasets.contains("*") || allowedDatasets.contains(datasetKey);
  }
}
