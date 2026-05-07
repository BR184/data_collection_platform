package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitlabMirrorPurgeService {

  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final GitlabSyncLogService logService;
  private final GitlabSyncTaskService taskService;
  private final GitlabWhitelistService whitelistService;

  @Transactional
  public MirrorPurgeResult purge(MirrorPurgeScope scope) {
    return purge(scope, null);
  }

  @Transactional
  public MirrorPurgeResult purge(MirrorPurgeScope scope, Long configId) {
    GitlabSyncConfig config = ensurePersistedConfig(resolveConfig(configId));
    if (config.getId() != null && taskService.hasActiveTask(config.getId())) {
      throw new BizException("当前存在运行中或排队中的同步任务，请先等待任务结束或手动中止后再删除镜像库数据");
    }

    List<String> droppedTables = new ArrayList<>();
    List<String> truncatedTables = new ArrayList<>();
    Set<String> preservedSourceTables = resolvePreservedSourceTables(config, scope);
    long syncLogId =
        logService.start(
            config.getId(),
            SyncType.PURGE,
            preservedSourceTables.stream().sorted().toList(),
            buildLogMessage(scope));

    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, "PURGE");
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Mirror_Purge")) {
      log.warn(
          "Mirror purge requested, configId={}, sourceInstance={}, scope={}",
          config.getId(),
          GitlabSourceInstanceSupport.sourceInstanceOf(config),
          scope);
      Set<String> preservedMirrorTables =
          resolvePreservedMirrorTablesFromRegistry(config.getId(), preservedSourceTables);

      for (String mirrorTable : listMirrorTables(config.getId())) {
        if (preservedMirrorTables.contains(mirrorTable)) {
          continue;
        }
        jdbcTemplate.execute("drop table if exists " + quoteIdentifier(mirrorTable));
        droppedTables.add(mirrorTable);
      }

      if (scope == MirrorPurgeScope.MIRROR_DATA_ONLY) {
        deleteMirrorRegistryForConfig(config.getId(), truncatedTables);
        deleteLegacyMirrorRecordsForConfig(config.getId(), truncatedTables);
      } else {
        deleteMirrorRegistryOutsideWhitelist(config.getId(), preservedMirrorTables, truncatedTables);
        deleteLegacyMirrorRecordsOutsideWhitelist(config.getId(), preservedSourceTables, truncatedTables);
      }

      boolean syncTimestampsReset = scope == MirrorPurgeScope.MIRROR_DATA_ONLY;
      if (config.getId() != null && syncTimestampsReset) {
        configService.resetSyncTime(config.getId());
      }

      log.warn(
          "Mirror purge completed, scope={}, droppedMirrorTables={}, truncatedTables={}",
          scope,
          droppedTables.size(),
          truncatedTables.size());

      MirrorPurgeResult result =
          new MirrorPurgeResult(
              scope,
              droppedTables.size(),
              droppedTables,
              truncatedTables.size(),
              truncatedTables,
              syncTimestampsReset);
      logService.finish(
          syncLogId,
          SyncStatus.SUCCESS,
          buildLogMessage(scope),
          affectedTableCount(result),
          0);
      return result;
    } catch (RuntimeException error) {
      logService.finish(
          syncLogId,
          SyncStatus.FAILED,
          buildFailureLogMessage(scope, error),
          droppedTables.size() + truncatedTables.size(),
          0);
      throw error;
    }
  }

  private List<String> listMirrorTables(Long configId) {
    if (configId == null) {
      return List.of();
    }
    return jdbcTemplate.queryForList(
        """
        select mirror_table_name
          from sys_table_registry
         where config_id = ?
         order by mirror_table_name
        """,
        String.class,
        configId);
  }

  private void truncateTable(String tableName, List<String> truncatedTables) {
    jdbcTemplate.execute("truncate table " + quoteIdentifier(tableName));
    truncatedTables.add(tableName);
  }

  private void deleteMirrorRegistryForConfig(Long configId, List<String> touchedTables) {
    jdbcTemplate.update("delete from \"sys_table_registry\" where config_id = ?", configId);
    touchedTables.add("sys_table_registry");
  }

  private void deleteLegacyMirrorRecordsForConfig(Long configId, List<String> touchedTables) {
    jdbcTemplate.update("delete from \"gitlab_mirror_records\" where config_id = ?", configId);
    touchedTables.add("gitlab_mirror_records");
  }

  private void deleteMirrorRegistryOutsideWhitelist(
      Long configId, Set<String> preservedMirrorTables, List<String> touchedTables) {
    if (preservedMirrorTables.isEmpty()) {
      deleteMirrorRegistryForConfig(configId, touchedTables);
      return;
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(preservedMirrorTables.size(), "?"));
    Object[] args = new Object[preservedMirrorTables.size() + 1];
    args[0] = configId;
    int index = 1;
    for (String table : preservedMirrorTables) {
      args[index++] = table;
    }
    jdbcTemplate.update(
        "delete from \"sys_table_registry\" where config_id = ? and mirror_table_name not in (" + placeholders + ")",
        args);
    touchedTables.add("sys_table_registry");
  }

  private void deleteLegacyMirrorRecordsOutsideWhitelist(
      Long configId, Set<String> preservedSourceTables, List<String> touchedTables) {
    if (preservedSourceTables.isEmpty()) {
      deleteLegacyMirrorRecordsForConfig(configId, touchedTables);
      return;
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(preservedSourceTables.size(), "?"));
    Object[] args = new Object[preservedSourceTables.size() + 1];
    args[0] = configId;
    int index = 1;
    for (String table : preservedSourceTables) {
      args[index++] = table;
    }
    jdbcTemplate.update(
        "delete from \"gitlab_mirror_records\" where config_id = ? and table_name not in (" + placeholders + ")",
        args);
    touchedTables.add("gitlab_mirror_records");
  }

  private Set<String> resolvePreservedSourceTables(GitlabSyncConfig config, MirrorPurgeScope scope) {
    if (scope != MirrorPurgeScope.MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST) {
      return Set.of();
    }
    return new HashSet<>(whitelistService.resolveOptions(config).stream().map(TableWhitelistOption::tableName).toList());
  }

  private Set<String> resolvePreservedMirrorTablesFromRegistry(Long configId, Set<String> preservedSourceTables) {
    if (preservedSourceTables.isEmpty()) {
      return Set.of();
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(preservedSourceTables.size(), "?"));
    Object[] args = new Object[preservedSourceTables.size() + 1];
    args[0] = configId;
    int index = 1;
    for (String table : preservedSourceTables) {
      args[index++] = table;
    }
    return new HashSet<>(jdbcTemplate.queryForList(
        "select mirror_table_name from \"sys_table_registry\" where config_id = ? and source_table_name in (" + placeholders + ")",
        String.class,
        args));
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private GitlabSyncConfig ensurePersistedConfig(GitlabSyncConfig config) {
    return config.getId() == null ? configService.saveConfig(config) : config;
  }

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  private String buildLogMessage(MirrorPurgeScope scope) {
    return switch (scope) {
      case MIRROR_DATA_ONLY -> "删除全部镜像数据";
      case MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST -> "删除白名单外镜像数据";
    };
  }

  private String buildFailureLogMessage(MirrorPurgeScope scope, RuntimeException error) {
    String detail = error.getMessage();
    if (detail == null || detail.isBlank()) {
      return buildLogMessage(scope) + "失败";
    }
    return buildLogMessage(scope) + "失败: " + detail;
  }

  private int affectedTableCount(MirrorPurgeResult result) {
    return result.droppedMirrorTables() + result.truncatedTables();
  }
}
