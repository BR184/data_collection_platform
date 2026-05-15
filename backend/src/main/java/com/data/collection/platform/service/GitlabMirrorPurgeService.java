package com.data.collection.platform.service;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
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
  private final GitlabWhitelistService whitelistService;

  @Transactional
  public MirrorPurgeResult purge(MirrorPurgeScope scope) {
    return purge(scope, null);
  }

  @Transactional
  public MirrorPurgeResult purge(MirrorPurgeScope scope, Long configId) {
    GitlabSyncConfig config = ensurePersistedConfig(resolveConfig(configId));
    List<String> droppedTables = new ArrayList<>();
    List<String> truncatedTables = new ArrayList<>();
    Set<String> preservedSourceTables = resolvePreservedSourceTables(config, scope);

    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "PURGE");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Mirror_Purge")) {
      log.warn(
          "Mirror purge requested, configId={}, sourceInstance={}, scope={}",
          config.getId(),
          GitlabSourceInstanceSupport.sourceInstanceOf(config),
          scope);
      Set<String> preservedMirrorTables = resolvePreservedMirrorTablesFromRegistry(config.getId(), preservedSourceTables);

      for (String mirrorTable : listMirrorTables(config.getId())) {
        if (preservedMirrorTables.contains(mirrorTable)) {
          continue;
        }
        jdbcTemplate.execute("drop table if exists " + quoteIdentifier(mirrorTable));
        droppedTables.add(mirrorTable);
      }

      if (scope == MirrorPurgeScope.MIRROR_DATA_ONLY) {
        deleteMirrorRegistryForConfig(config.getId(), truncatedTables);
        deleteMirrorRecordsForConfig(config.getId(), truncatedTables);
      } else {
        deleteMirrorRegistryOutsideWhitelist(config.getId(), preservedMirrorTables, truncatedTables);
        deleteMirrorRecordsOutsideWhitelist(config.getId(), preservedSourceTables, truncatedTables);
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
      return new MirrorPurgeResult(
          scope,
          droppedTables.size(),
          droppedTables,
          truncatedTables.size(),
          truncatedTables,
          syncTimestampsReset);
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

  private void deleteMirrorRegistryForConfig(Long configId, List<String> touchedTables) {
    jdbcTemplate.update("delete from \"sys_table_registry\" where config_id = ?", configId);
    touchedTables.add("sys_table_registry");
  }

  private void deleteMirrorRecordsForConfig(Long configId, List<String> touchedTables) {
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

  private void deleteMirrorRecordsOutsideWhitelist(
      Long configId, Set<String> preservedSourceTables, List<String> touchedTables) {
    if (preservedSourceTables.isEmpty()) {
      deleteMirrorRecordsForConfig(configId, touchedTables);
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
}
