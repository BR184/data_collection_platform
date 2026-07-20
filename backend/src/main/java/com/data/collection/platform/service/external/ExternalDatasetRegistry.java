package com.data.collection.platform.service.external;

import com.data.collection.platform.entity.external.ExternalDatasetDescriptor;
import com.data.collection.platform.security.ExternalApiClientPrincipal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExternalDatasetRegistry {
  private final Map<String, ExternalDatasetProvider<?>> providers;

  public ExternalDatasetRegistry(List<ExternalDatasetProvider<?>> providers) {
    this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
        ExternalDatasetProvider::datasetKey,
        Function.identity(),
        (left, right) -> {
          throw new IllegalStateException("重复的外部数据集键: " + left.datasetKey());
        }));
  }

  public List<ExternalDatasetDescriptor> descriptors(ExternalApiClientPrincipal client) {
    return providers.values().stream()
        .filter(provider -> client.canRead(provider.datasetKey()))
        .map(ExternalDatasetProvider::descriptor)
        .sorted(Comparator.comparing(ExternalDatasetDescriptor::datasetKey))
        .toList();
  }

  public ExternalDatasetProvider<?> provider(String datasetKey, ExternalApiClientPrincipal client) {
    ExternalDatasetProvider<?> provider = providers.get(datasetKey);
    if (provider == null) {
      return null;
    }
    return client.canRead(datasetKey) ? provider : null;
  }

  public boolean exists(String datasetKey) {
    return providers.containsKey(datasetKey);
  }
}
