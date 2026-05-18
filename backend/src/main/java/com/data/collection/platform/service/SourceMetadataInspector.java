package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SourceMetadataInspector {
  private final GitlabExternalDbService externalDbService;

  public SourceMetadataInspector(GitlabExternalDbService externalDbService) {
    this.externalDbService = externalDbService;
  }

  public List<TableWhitelistOption> discoverTables(
      GitlabSyncConfig config,
      Map<String, String> labels,
      List<String> recommendedTables) {
    return externalDbService.discoverTables(config, labels, recommendedTables);
  }

  public SourceTableSchema discoverTableSchema(GitlabSyncConfig config, TableWhitelistOption option) {
    return externalDbService.discoverTableSchema(config, option);
  }
}
