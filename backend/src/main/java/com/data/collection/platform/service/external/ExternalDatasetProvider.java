package com.data.collection.platform.service.external;

import com.data.collection.platform.entity.external.ExternalDatasetDescriptor;
import java.util.Map;

public interface ExternalDatasetProvider<T> {
  String datasetKey();

  ExternalDatasetDescriptor descriptor();

  T load(Map<String, String> parameters);
}
