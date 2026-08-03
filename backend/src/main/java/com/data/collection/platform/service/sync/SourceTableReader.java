package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabTableProbe;
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
      SourceTableSchema mirrorSchema,
      LocalDateTime watermark,
      LocalDateTime upperBound,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    return externalDbService.incrementalCursorScan(
        config,
        option,
        mirrorSchema,
        watermark,
        upperBound,
        cursorUpdatedAt,
        cursorPk,
        batchSize);
  }

  /** 按完整范围读取来源当前集合，供所有精确任务使用。 */
  public List<Map<String, Object>> readPrecise(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      Map<String, Object> lookupScope) {
    return externalDbService.preciseScan(config, option, lookupScope);
  }

  /** 批量读取一组同构权威范围，缺失范围保留为空集合。 */
  public Map<Long, List<Map<String, Object>>> readAuthoritativeScopes(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      Map<Long, Map<String, Object>> lookupScopes) {
    return externalDbService.authoritativeScopeScan(config, option, schema, lookupScopes);
  }

  public LocalDateTime findMaxUpdatedAt(GitlabSyncConfig config, TableWhitelistOption option) {
    return externalDbService.findMaxUpdatedAt(config, option);
  }

  public GitlabTableProbe probeTable(GitlabSyncConfig config, TableWhitelistOption option) {
    return externalDbService.probeTable(config, option);
  }

  public Set<String> findExistingPrimaryKeySignatures(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<Map<String, Object>> primaryKeyRows) {
    return externalDbService.findExistingPrimaryKeySignatures(
        config, option, schema, primaryKeyRows);
  }
}
