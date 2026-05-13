package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabMirrorSchemaService {
  private static final int POSTGRES_IDENTIFIER_MAX_LENGTH = 63;
  private static final TypeReference<List<SourceTableColumn>> COLUMN_LIST_TYPE = new TypeReference<>() {};

  private final GitlabExternalDbService externalDbService;
  private final GitlabMirrorTableRegistryMapper registryMapper;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final GitlabMirrorProperties properties;

  public GitlabMirrorSchemaService(
      GitlabExternalDbService externalDbService,
      GitlabMirrorTableRegistryMapper registryMapper,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      GitlabMirrorProperties properties) {
    this.externalDbService = externalDbService;
    this.registryMapper = registryMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.properties = properties;
  }

  /**
   * Control-plane schema refresh. This path may do low-frequency schema evolution work.
   */
  public PreparedMirrorTable prepareMirrorTable(GitlabSyncConfig config, TableWhitelistOption option) {
    String expectedMirrorTableName = buildMirrorTableName(config, option.tableName());
    GitlabMirrorTableRegistry registry = findRegistry(config.getId(), option.tableName());
    if (registry != null
        && Boolean.TRUE.equals(registry.getInitialized())
        && expectedMirrorTableName.equals(registry.getMirrorTableName())
        && !isSchemaCheckDue(registry)
        && tableExists(registry.getMirrorTableName())) {
      SourceTableSchema cachedSchema = buildSchemaFromRegistry(registry);
      return new PreparedMirrorTable(cachedSchema, registry.getMirrorTableName(), true, registry);
    }

    SourceTableSchema sourceSchema = externalDbService.discoverTableSchema(config, option);
    String schemaFingerprint = buildSchemaFingerprint(sourceSchema);
    String mirrorTableName =
        registry != null
                && expectedMirrorTableName.equals(registry.getMirrorTableName())
                && registry.getMirrorTableName() != null
                && !registry.getMirrorTableName().isBlank()
            ? registry.getMirrorTableName()
            : expectedMirrorTableName;

    boolean schemaChanged =
        registry == null
            || !Boolean.TRUE.equals(registry.getInitialized())
            || !tableExists(mirrorTableName)
            || !schemaFingerprint.equals(registry.getSchemaFingerprint());

    if (schemaChanged) {
      ensureMirrorTableExists(mirrorTableName, sourceSchema);
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Schema_Sync")) {
        log.info(
            "Mirror schema synced, sourceTableName={}, mirrorTableName={}, schemaFingerprint={}",
            option.tableName(),
            mirrorTableName,
            schemaFingerprint);
      }
    } else {
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Schema_Check")) {
        log.info(
            "Mirror schema reused from registry, sourceTableName={}, mirrorTableName={}, schemaFingerprint={}",
            option.tableName(),
            mirrorTableName,
            schemaFingerprint);
      }
    }

    GitlabMirrorTableRegistry updatedRegistry =
        upsertRegistry(config, option, sourceSchema, mirrorTableName, schemaFingerprint, true);
    return new PreparedMirrorTable(
        new SourceTableSchema(mirrorTableName, sourceSchema.primaryKeys(), sourceSchema.updatedAtColumn(), sourceSchema.columns()),
        mirrorTableName,
        !schemaChanged,
        updatedRegistry);
  }

  /**
   * Data-plane fast path. Never performs ALTER/type validation/auxiliary index work.
   */
  public PreparedMirrorTable getPreparedMirrorTableForSync(GitlabSyncConfig config, TableWhitelistOption option) {
    String expectedMirrorTableName = buildMirrorTableName(config, option.tableName());
    GitlabMirrorTableRegistry registry = findRegistry(config.getId(), option.tableName());
    if (registry != null
        && Boolean.TRUE.equals(registry.getInitialized())
        && Boolean.TRUE.equals(registry.getPreviewEnabled())
        && expectedMirrorTableName.equals(registry.getMirrorTableName())
        && !isSchemaCheckDue(registry)
        && tableExists(registry.getMirrorTableName())) {
      SourceTableSchema cachedSchema = buildSchemaFromRegistry(registry);
      return new PreparedMirrorTable(cachedSchema, registry.getMirrorTableName(), true, registry);
    }

    if (registry != null
        && Boolean.TRUE.equals(registry.getInitialized())
        && Boolean.TRUE.equals(registry.getPreviewEnabled())
        && expectedMirrorTableName.equals(registry.getMirrorTableName())
        && tableExists(registry.getMirrorTableName())) {
      return prepareMirrorTable(config, option);
    }

    SourceTableSchema sourceSchema = externalDbService.discoverTableSchema(config, option);
    String mirrorTableName =
        registry != null
                && expectedMirrorTableName.equals(registry.getMirrorTableName())
                && registry.getMirrorTableName() != null
                && !registry.getMirrorTableName().isBlank()
            ? registry.getMirrorTableName()
            : expectedMirrorTableName;
    ensureMirrorTableForSync(mirrorTableName, sourceSchema);
    String schemaFingerprint = buildSchemaFingerprint(sourceSchema);
    GitlabMirrorTableRegistry updatedRegistry =
        upsertRegistry(config, option, sourceSchema, mirrorTableName, schemaFingerprint, true);
    try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Schema_Bootstrap")) {
      log.info(
          "Mirror table bootstrapped for sync, sourceTableName={}, mirrorTableName={}, schemaFingerprint={}",
          option.tableName(),
          mirrorTableName,
          schemaFingerprint);
    }
    return new PreparedMirrorTable(
        new SourceTableSchema(mirrorTableName, sourceSchema.primaryKeys(), sourceSchema.updatedAtColumn(), sourceSchema.columns()),
        mirrorTableName,
        false,
        updatedRegistry);
  }

  public void markTableSyncing(Long configId, String sourceTableName) {
    updateSyncStatus(configId, sourceTableName, "SYNCING", null);
  }

  public void markTableIdle(Long configId, String sourceTableName, LocalDateTime lastSyncTime) {
    updateSyncStatus(configId, sourceTableName, "IDLE", lastSyncTime);
  }

  public void markTableError(Long configId, String sourceTableName) {
    updateSyncStatus(configId, sourceTableName, "ERROR", null);
  }

  public int recoverStaleSyncingStatuses() {
    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(30, properties.getHeartbeatTimeoutSeconds()));
    try {
      Integer updated = jdbcTemplate.queryForObject(
          """
              with recovered as (
                update sys_table_registry
                set sync_status = 'IDLE',
                    updated_at = current_timestamp
                where sync_status = 'SYNCING'
                  and updated_at < ?
                returning id
              )
              select count(*) from recovered
              """,
          Integer.class,
          cutoff);
      return updated == null ? 0 : updated;
    } catch (DataAccessException error) {
      log.debug("Skipped stale mirror status recovery because registry table is unavailable", error);
      return 0;
    }
  }

  public List<GitlabMirrorTableRegistry> listRegistry(Long configId) {
    return registryMapper.selectList(new LambdaQueryWrapper<GitlabMirrorTableRegistry>()
        .eq(GitlabMirrorTableRegistry::getConfigId, configId)
        .eq(GitlabMirrorTableRegistry::getInitialized, true)
        .eq(GitlabMirrorTableRegistry::getPreviewEnabled, true)
        .orderByAsc(GitlabMirrorTableRegistry::getSourceTableName));
  }

  private GitlabMirrorTableRegistry findRegistry(Long configId, String sourceTableName) {
    return registryMapper.selectOne(new LambdaQueryWrapper<GitlabMirrorTableRegistry>()
        .eq(GitlabMirrorTableRegistry::getConfigId, configId)
        .eq(GitlabMirrorTableRegistry::getSourceTableName, sourceTableName)
        .last("limit 1"));
  }

  private boolean isSchemaCheckDue(GitlabMirrorTableRegistry registry) {
    if (registry.getLastSchemaCheckTime() == null) {
      return true;
    }
    Duration elapsed = Duration.between(registry.getLastSchemaCheckTime(), LocalDateTime.now());
    return elapsed.toMinutes() >= properties.getSchemaCheckIntervalMinutes();
  }

  private boolean tableExists(String tableName) {
    Integer count = jdbcTemplate.queryForObject(
        """
            select count(*)
            from pg_tables
            where schemaname = current_schema()
              and tablename = ?
            """,
        Integer.class,
        tableName);
    return count != null && count > 0;
  }

  private SourceTableSchema buildSchemaFromRegistry(GitlabMirrorTableRegistry registry) {
    List<String> primaryKeys = registry.getPrimaryKeyColumns() == null
        ? List.of()
        : List.of(registry.getPrimaryKeyColumns().split(",")).stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    List<SourceTableColumn> columns = jsonUtils.fromJson(registry.getColumnSnapshot(), COLUMN_LIST_TYPE);
    if (columns == null || columns.isEmpty()) {
      throw new IllegalStateException("Registry column snapshot is empty for " + registry.getSourceTableName());
    }
    return new SourceTableSchema(
        registry.getMirrorTableName(),
        primaryKeys,
        registry.getUpdatedAtColumn(),
        columns);
  }

  private GitlabMirrorTableRegistry upsertRegistry(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema sourceSchema,
      String mirrorTableName,
      String schemaFingerprint,
      boolean initialized) {
    GitlabMirrorTableRegistry current = findRegistry(config.getId(), option.tableName());
    LocalDateTime now = LocalDateTime.now();
    GitlabMirrorTableRegistry registry = current == null ? new GitlabMirrorTableRegistry() : current;
    registry.setConfigId(config.getId());
    registry.setSourceTableName(option.tableName());
    registry.setMirrorTableName(mirrorTableName);
    registry.setSchemaFingerprint(schemaFingerprint);
    registry.setInitialized(initialized);
    registry.setPreviewEnabled(current == null || current.getPreviewEnabled() == null ? Boolean.TRUE : current.getPreviewEnabled());
    registry.setColumnSnapshot(jsonUtils.toJson(sourceSchema.columns()));
    registry.setPrimaryKeyColumns(String.join(",", sourceSchema.primaryKeys()));
    registry.setUpdatedAtColumn(sourceSchema.updatedAtColumn());
    registry.setLastSchemaCheckTime(now);
    registry.setSyncStatus(current == null || current.getSyncStatus() == null ? "IDLE" : current.getSyncStatus());
    registry.setUpdatedAt(now);
    if (current == null) {
      registry.setCreatedAt(now);
      jdbcTemplate.update(
          """
              insert into sys_table_registry(
                config_id,
                source_table_name,
                mirror_table_name,
                schema_fingerprint,
                is_initialized,
                last_sync_time,
                last_schema_check_time,
                sync_status,
                preview_enabled,
                column_snapshot,
                primary_key_columns,
                updated_at_column,
                created_at,
                updated_at
              ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
              """,
          registry.getConfigId(),
          registry.getSourceTableName(),
          registry.getMirrorTableName(),
          registry.getSchemaFingerprint(),
          registry.getInitialized(),
          registry.getLastSyncTime(),
          registry.getLastSchemaCheckTime(),
          registry.getSyncStatus(),
          registry.getPreviewEnabled(),
          registry.getColumnSnapshot(),
          registry.getPrimaryKeyColumns(),
          registry.getUpdatedAtColumn(),
          registry.getCreatedAt(),
          registry.getUpdatedAt());
      return findRegistry(config.getId(), option.tableName());
    }

    jdbcTemplate.update(
        """
            update sys_table_registry
            set mirror_table_name = ?,
                schema_fingerprint = ?,
                is_initialized = ?,
                last_schema_check_time = ?,
                sync_status = ?,
                preview_enabled = ?,
                column_snapshot = cast(? as jsonb),
                primary_key_columns = ?,
                updated_at_column = ?,
                updated_at = ?
            where id = ?
            """,
        registry.getMirrorTableName(),
        registry.getSchemaFingerprint(),
        registry.getInitialized(),
        registry.getLastSchemaCheckTime(),
        registry.getSyncStatus(),
        registry.getPreviewEnabled(),
        registry.getColumnSnapshot(),
        registry.getPrimaryKeyColumns(),
        registry.getUpdatedAtColumn(),
        registry.getUpdatedAt(),
        registry.getId());
    return findRegistry(config.getId(), option.tableName());
  }

  private void updateSyncStatus(Long configId, String sourceTableName, String syncStatus, LocalDateTime lastSyncTime) {
    GitlabMirrorTableRegistry registry = findRegistry(configId, sourceTableName);
    if (registry == null) {
      return;
    }
    registry.setSyncStatus(syncStatus);
    if (lastSyncTime != null) {
      registry.setLastSyncTime(lastSyncTime);
    }
    registry.setUpdatedAt(LocalDateTime.now());
    jdbcTemplate.update(
        """
            update sys_table_registry
            set sync_status = ?,
                last_sync_time = coalesce(?, last_sync_time),
                updated_at = ?
            where id = ?
            """,
        registry.getSyncStatus(),
        registry.getLastSyncTime(),
        registry.getUpdatedAt(),
        registry.getId());
  }

  /**
   * Control-plane sync path: can evolve structure when explicitly requested.
   */
  private void ensureMirrorTableExists(String mirrorTableName, SourceTableSchema schema) {
    jdbcTemplate.execute(buildCreateTableSql(mirrorTableName, schema));
    for (SourceTableColumn column : schema.columns()) {
      jdbcTemplate.execute(
          "alter table " + quoteIdentifier(mirrorTableName)
              + " add column if not exists "
              + quoteIdentifier(column.columnName())
              + " "
              + column.formattedType());
    }
    ensureBaseMetadataColumns(mirrorTableName);
    validateColumnTypes(mirrorTableName, schema);
    ensurePrimaryKeyIndex(mirrorTableName, schema.primaryKeys());
  }

  /**
   * Data-plane fast path: minimal create + core index only.
   */
  private void ensureMirrorTableForSync(String mirrorTableName, SourceTableSchema schema) {
    jdbcTemplate.execute(buildCreateTableSql(mirrorTableName, schema));
    ensurePrimaryKeyIndex(mirrorTableName, schema.primaryKeys());
  }

  private String buildCreateTableSql(String mirrorTableName, SourceTableSchema schema) {
    List<String> definitions = new ArrayList<>();
    definitions.add("mirror_id bigserial primary key");
    for (SourceTableColumn column : schema.columns()) {
      StringBuilder builder = new StringBuilder()
          .append(quoteIdentifier(column.columnName()))
          .append(' ')
          .append(column.formattedType());
      if (!column.nullable()) {
        builder.append(" not null");
      }
      definitions.add(builder.toString());
    }
    definitions.add("mirror_task_id bigint");
    definitions.add("source_updated_at timestamp");
    definitions.add("mirror_synced_at timestamp not null default current_timestamp");
    definitions.add("mirror_deleted boolean not null default false");
    definitions.add("mirror_created_at timestamp not null default current_timestamp");
    definitions.add("mirror_updated_at timestamp not null default current_timestamp");
    return "create table if not exists " + quoteIdentifier(mirrorTableName) + " ("
        + String.join(", ", definitions) + ")";
  }

  private void ensureBaseMetadataColumns(String mirrorTableName) {
    jdbcTemplate.execute("alter table " + quoteIdentifier(mirrorTableName) + " add column if not exists mirror_task_id bigint");
    jdbcTemplate.execute("alter table " + quoteIdentifier(mirrorTableName) + " add column if not exists source_updated_at timestamp");
    jdbcTemplate.execute("alter table " + quoteIdentifier(mirrorTableName) + " add column if not exists mirror_synced_at timestamp not null default current_timestamp");
    jdbcTemplate.execute("alter table " + quoteIdentifier(mirrorTableName) + " add column if not exists mirror_deleted boolean not null default false");
    jdbcTemplate.execute("alter table " + quoteIdentifier(mirrorTableName) + " add column if not exists mirror_created_at timestamp not null default current_timestamp");
    jdbcTemplate.execute("alter table " + quoteIdentifier(mirrorTableName) + " add column if not exists mirror_updated_at timestamp not null default current_timestamp");
  }

  private void validateColumnTypes(String mirrorTableName, SourceTableSchema schema) {
    Map<String, String> localTypes = jdbcTemplate.query(
        """
            select a.attname as column_name, pg_catalog.format_type(a.atttypid, a.atttypmod) as formatted_type
            from pg_attribute a
            join pg_class c on a.attrelid = c.oid
            join pg_namespace n on c.relnamespace = n.oid
            where n.nspname = current_schema()
              and c.relname = ?
              and a.attnum > 0
              and not a.attisdropped
            """,
        ps -> ps.setString(1, mirrorTableName),
        rs -> {
          Map<String, String> result = new java.util.LinkedHashMap<>();
          while (rs.next()) {
            result.put(rs.getString("column_name"), rs.getString("formatted_type"));
          }
          return result;
        });
    for (SourceTableColumn column : schema.columns()) {
      String localType = localTypes.get(column.columnName());
      if (localType != null && !normalizeType(localType).equals(normalizeType(column.formattedType()))) {
        throw new IllegalStateException(
            "Mirror table column type mismatch: %s.%s local=%s, source=%s"
                .formatted(mirrorTableName, column.columnName(), localType, column.formattedType()));
      }
    }
  }

  private void ensurePrimaryKeyIndex(String mirrorTableName, List<String> primaryKeys) {
    if (primaryKeys == null || primaryKeys.isEmpty()) {
      return;
    }
    String indexName = abbreviateIdentifier("uq_" + mirrorTableName + "_pk");
    String columns = primaryKeys.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
    jdbcTemplate.execute(
        "create unique index if not exists " + quoteIdentifier(indexName) + " on " + quoteIdentifier(mirrorTableName) + " (" + columns + ")");
  }

  private String buildSchemaFingerprint(SourceTableSchema schema) {
    String payload = schema.tableName()
        + "|pk=" + String.join(",", schema.primaryKeys())
        + "|updated=" + (schema.updatedAtColumn() == null ? "" : schema.updatedAtColumn())
        + "|columns=" + schema.columns().stream()
            .map(column -> column.columnName() + ":" + column.formattedType() + ":" + column.nullable())
            .collect(Collectors.joining("|"));
    return shortHash(payload);
  }

  private String buildMirrorTableName(GitlabSyncConfig config, String sourceTableName) {
    return GitlabSourceInstanceSupport.buildMirrorTableName(
        sourceTableName,
        GitlabSourceInstanceSupport.sourceInstanceOf(config));
  }

  private String shortHash(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to hash schema fingerprint", e);
    }
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private String abbreviateIdentifier(String identifier) {
    if (identifier.length() <= POSTGRES_IDENTIFIER_MAX_LENGTH) {
      return identifier;
    }
    return identifier.substring(0, POSTGRES_IDENTIFIER_MAX_LENGTH - 17) + "_" + shortHash(identifier);
  }

  private String normalizeType(String type) {
    return type == null ? "" : type.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }

  public record PreparedMirrorTable(
      SourceTableSchema mirrorSchema,
      String mirrorTableName,
      boolean fastPath,
      GitlabMirrorTableRegistry registry) {
  }
}
