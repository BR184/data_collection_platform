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
    GitlabSyncConfig config = ensurePersistedConfig(configService.getConfig());
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
      log.warn("Mirror purge requested, scope={}", scope);
      Set<String> preservedMirrorTables = resolvePreservedMirrorTablesFromRegistry(preservedSourceTables);

      for (String mirrorTable : listMirrorTables()) {
        if (preservedMirrorTables.contains(mirrorTable)) {
          continue;
        }
        jdbcTemplate.execute("drop table if exists " + quoteIdentifier(mirrorTable));
        droppedTables.add(mirrorTable);
      }

      if (scope == MirrorPurgeScope.MIRROR_DATA_ONLY) {
        truncateTable("sys_table_registry", truncatedTables);
        truncateTable("gitlab_mirror_records", truncatedTables);
      } else {
        deleteMirrorRegistryOutsideWhitelist(preservedMirrorTables, truncatedTables);
        deleteLegacyMirrorRecordsOutsideWhitelist(preservedSourceTables, truncatedTables);
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

  private List<String> listMirrorTables() {
    return jdbcTemplate.queryForList(
        """
        select tablename
        from pg_tables
        where schemaname = current_schema()
          and tablename like 'ods_gitlab_%'
        order by tablename
        """,
        String.class);
  }

  private void truncateTable(String tableName, List<String> truncatedTables) {
    jdbcTemplate.execute("truncate table " + quoteIdentifier(tableName));
    truncatedTables.add(tableName);
  }

  private void deleteMirrorRegistryOutsideWhitelist(Set<String> preservedMirrorTables, List<String> touchedTables) {
    if (preservedMirrorTables.isEmpty()) {
      truncateTable("sys_table_registry", touchedTables);
      return;
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(preservedMirrorTables.size(), "?"));
    jdbcTemplate.update(
        "delete from \"sys_table_registry\" where mirror_table_name not in (" + placeholders + ")",
        preservedMirrorTables.toArray());
    touchedTables.add("sys_table_registry");
  }

  private void deleteLegacyMirrorRecordsOutsideWhitelist(Set<String> preservedSourceTables, List<String> touchedTables) {
    if (preservedSourceTables.isEmpty()) {
      truncateTable("gitlab_mirror_records", touchedTables);
      return;
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(preservedSourceTables.size(), "?"));
    jdbcTemplate.update(
        "delete from \"gitlab_mirror_records\" where table_name not in (" + placeholders + ")",
        preservedSourceTables.toArray());
    touchedTables.add("gitlab_mirror_records");
  }

  private Set<String> resolvePreservedSourceTables(GitlabSyncConfig config, MirrorPurgeScope scope) {
    if (scope != MirrorPurgeScope.MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST) {
      return Set.of();
    }
    return new HashSet<>(whitelistService.resolveOptions(config).stream().map(TableWhitelistOption::tableName).toList());
  }

  private Set<String> resolvePreservedMirrorTablesFromRegistry(Set<String> preservedSourceTables) {
    if (preservedSourceTables.isEmpty()) {
      return Set.of();
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(preservedSourceTables.size(), "?"));
    return new HashSet<>(jdbcTemplate.queryForList(
        "select mirror_table_name from \"sys_table_registry\" where source_table_name in (" + placeholders + ")",
        String.class,
        preservedSourceTables.toArray()));
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private GitlabSyncConfig ensurePersistedConfig(GitlabSyncConfig config) {
    return config.getId() == null ? configService.saveConfig(config) : config;
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
