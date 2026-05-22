package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.service.GitlabExternalDbService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SourceTableReader {
  private final GitlabExternalDbService externalDbService;

  public SourceTableReader(GitlabExternalDbService externalDbService) {
    this.externalDbService = externalDbService;
  }

  public List<Map<String, Object>> readFullBatch(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema mirrorSchema,
      String cursorPk,
      int batchSize) {
    return externalDbService.fullCursorScan(config, option, mirrorSchema, cursorPk, batchSize);
  }

  public List<Map<String, Object>> readIncrementalBatch(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      LocalDateTime watermark,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    return externalDbService.incrementalCursorScan(config, option, watermark, cursorUpdatedAt, cursorPk, batchSize);
  }

  public List<Map<String, Object>> readPrecise(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      String lookupColumn,
      Object lookupValue) {
    return externalDbService.preciseScan(config, option, lookupColumn, lookupValue);
  }

  public LocalDateTime findMaxUpdatedAt(GitlabSyncConfig config, TableWhitelistOption option) {
    return externalDbService.findMaxUpdatedAt(config, option);
  }

  public Set<String> findExistingPrimaryKeySignatures(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      List<Map<String, Object>> primaryKeyRows) {
    return externalDbService.findExistingPrimaryKeySignatures(config, option, primaryKeyRows);
  }
}
