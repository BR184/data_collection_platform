package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.database.DatabaseTableOption;
import com.data.collection.platform.entity.database.DatabaseTableRowsResponse;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
// 数据库浏览服务只暴露白名单内的业务表和镜像表，用于运维排查而不是任意 SQL 控制台。
// 表定义负责限制可查列、可排序字段和关键词范围，避免前端直接拼接表结构。
public class DatabaseBrowserService {
  private static final String TABLE_KIND_LOCAL = "LOCAL";
  private static final String TABLE_KIND_MIRROR = "MIRROR";
  private static final String TABLE_KIND_SOURCE = "SOURCE";
  private static final String SOURCE_PREFIX = "source:";

  private final JdbcTemplate jdbcTemplate;
  private final GitlabMirrorTableRegistryMapper registryMapper;
  private final DatabaseBrowserMirrorTableDefinitionFactory mirrorTableDefinitionFactory;
  private final GitlabMirrorSyncService gitlabMirrorSyncService;
  private final GitlabConfigService configService;
  private final SourceMetadataInspector sourceMetadataInspector;
  private final GitlabExternalDbService externalDbService;

  public DatabaseBrowserService(
      JdbcTemplate jdbcTemplate,
      GitlabMirrorTableRegistryMapper registryMapper,
      DatabaseBrowserMirrorTableDefinitionFactory mirrorTableDefinitionFactory,
      GitlabMirrorSyncService gitlabMirrorSyncService,
      GitlabConfigService configService,
      SourceMetadataInspector sourceMetadataInspector,
      GitlabExternalDbService externalDbService) {
    this.jdbcTemplate = jdbcTemplate;
    this.registryMapper = registryMapper;
    this.mirrorTableDefinitionFactory = mirrorTableDefinitionFactory;
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
    this.configService = configService;
    this.sourceMetadataInspector = sourceMetadataInspector;
    this.externalDbService = externalDbService;
  }

  public List<DatabaseTableOption> listTables() {
    Map<String, DatabaseTableOption> allTables = new LinkedHashMap<>();
    DatabaseBrowserTableCatalog.listDefinitions()
        .forEach(
            (tableName, definition) ->
                allTables.put(
                    tableName,
                    new DatabaseTableOption(tableName, definition.label(), "IDLE", null, TABLE_KIND_LOCAL, false)));
    listMirrorRegistries()
        .forEach(
            registry -> {
              allTables.put(
                  registry.getMirrorTableName(),
                  new DatabaseTableOption(
                      registry.getMirrorTableName(),
                      mirrorTableDefinitionFactory.buildMirrorLabel(registry.getSourceTableName()),
                      registry.getSyncStatus(),
                      registry.getLastSyncTime(),
                      TABLE_KIND_MIRROR,
                      true));
              allTables.put(
                  sourceTableKey(registry),
                  new DatabaseTableOption(
                      sourceTableKey(registry),
                      buildSourceLabel(registry),
                      registry.getSyncStatus(),
                      registry.getLastSyncTime(),
                      TABLE_KIND_SOURCE,
                      false));
            });
    return allTables.values().stream()
        .sorted((left, right) -> left.getTableName().compareToIgnoreCase(right.getTableName()))
        .toList();
  }

  public DatabaseTableRowsResponse getTableRows(
      String tableName,
      Integer page,
      Integer size,
      String keyword,
      String sortField,
      String sortOrder) {
    if (isSourceTableKey(tableName)) {
      return getSourceTableRows(tableName, page, size, keyword, sortField, sortOrder);
    }
    DatabaseBrowserTableContext context = resolveTableContext(tableName);
    int safePage = DatabaseBrowserQuerySupport.normalizePage(page);
    int safeSize = DatabaseBrowserQuerySupport.normalizeSize(size);
    String validatedSortField =
        DatabaseBrowserQuerySupport.normalizeSortField(context.definition(), sortField);
    String validatedSortOrder = DatabaseBrowserQuerySupport.normalizeSortOrder(sortOrder);
    String normalizedKeyword = DatabaseBrowserQuerySupport.normalizeKeyword(keyword);

    DatabaseBrowserSqlBundle sqlBundle =
        DatabaseBrowserQuerySupport.buildSql(
            context.definition(),
            tableName,
            normalizedKeyword,
            validatedSortField,
            validatedSortOrder,
            safePage,
            safeSize);
    long total =
        jdbcTemplate.queryForObject(
            sqlBundle.countSql(),
            Long.class,
            sqlBundle.arguments().toArray());
    List<Map<String, Object>> rows =
        jdbcTemplate.query(
            sqlBundle.rowsSql(),
            DatabaseBrowserRowMapperFactory.createTableRowMapper(),
            sqlBundle.arguments().toArray());

    return new DatabaseTableRowsResponse(
        tableName,
        context.definition().label(),
        context.definition().columns(),
        rows,
        total,
        safePage,
        safeSize,
        validatedSortField,
        validatedSortOrder,
        normalizedKeyword,
        context.registry() == null ? "IDLE" : context.registry().getSyncStatus(),
        context.registry() == null ? null : context.registry().getLastSyncTime(),
        buildStatusMessage(context.registry()),
        context.registry() == null ? TABLE_KIND_LOCAL : TABLE_KIND_MIRROR,
        context.registry() != null);
  }

  public int refreshTable(String tableName) {
    return refreshTableDetailed(tableName).plannedTasks();
  }

  public GitlabMirrorSyncService.OnDemandRefreshResult refreshTableDetailed(String tableName) {
    DatabaseBrowserTableContext context = resolveTableContext(tableName);
    GitlabMirrorTableRegistry registry = context.registry();
    if (registry == null) {
      return new GitlabMirrorSyncService.OnDemandRefreshResult(
          null,
          List.of(),
          0,
          List.of(),
          com.data.collection.platform.entity.SyncStatus.IDLE,
          "Current table does not require mirror refresh");
    }
    return gitlabMirrorSyncService.refreshTablesOnDemandDetailed(
        registry.getConfigId(),
        List.of(registry.getSourceTableName()),
        "database-browser:" + tableName);
  }

  private List<GitlabMirrorTableRegistry> listMirrorRegistries() {
    return registryMapper.selectList(
        new LambdaQueryWrapper<GitlabMirrorTableRegistry>()
            .eq(GitlabMirrorTableRegistry::getInitialized, true)
            .orderByAsc(GitlabMirrorTableRegistry::getSourceTableName));
  }

  private DatabaseTableRowsResponse getSourceTableRows(
      String tableName,
      Integer page,
      Integer size,
      String keyword,
      String sortField,
      String sortOrder) {
    SourceTableSelection selection = parseSourceTableKey(tableName);
    GitlabSyncConfig config = configService.getConfigById(selection.configId());
    if (!isSourceEnabled(config)) {
      throw new BizException("source is disabled");
    }
    if (!configService.isSourceConfigured(config)) {
      throw new BizException("source connection settings are incomplete");
    }
    GitlabMirrorTableRegistry registry = findInitializedRegistry(selection.configId(), selection.sourceTable());
    TableWhitelistOption option = new TableWhitelistOption(
        registry.getSourceTableName(),
        registry.getSourceTableName(),
        registry.getPrimaryKeyColumns(),
        registry.getUpdatedAtColumn(),
        false);
    SourceTableSchema schema = sourceMetadataInspector.discoverTableSchema(config, option);
    DatabaseBrowserTableDefinition definition = buildSourceTableDefinition(schema, registry);
    int safePage = DatabaseBrowserQuerySupport.normalizePage(page);
    int safeSize = DatabaseBrowserQuerySupport.normalizeSize(size);
    String validatedSortField =
        DatabaseBrowserQuerySupport.normalizeSortField(definition, sortField);
    String validatedSortOrder = DatabaseBrowserQuerySupport.normalizeSortOrder(sortOrder);
    String normalizedKeyword = DatabaseBrowserQuerySupport.normalizeKeyword(keyword);
    List<Map<String, Object>> rows = externalDbService.previewTablePage(
        config,
        option,
        schema,
        normalizedKeyword,
        validatedSortField,
        validatedSortOrder,
        safePage,
        safeSize);
    boolean hasMore = rows.size() == safeSize;
    long visibleTotal = ((long) safePage - 1) * safeSize + rows.size() + (hasMore ? 1 : 0);
    return new DatabaseTableRowsResponse(
        tableName,
        buildSourceLabel(registry),
        definition.columns(),
        rows,
        visibleTotal,
        safePage,
        safeSize,
        validatedSortField,
        validatedSortOrder,
        normalizedKeyword,
        registry.getSyncStatus(),
        registry.getLastSyncTime(),
        "来源表为管理员实时只读预览；为保护百万级内网源库，当前页不执行全表 count。",
        TABLE_KIND_SOURCE,
        false);
  }

  private DatabaseBrowserTableDefinition buildSourceTableDefinition(
      SourceTableSchema schema,
      GitlabMirrorTableRegistry registry) {
    List<com.data.collection.platform.entity.database.DatabaseTableColumn> columns = new ArrayList<>();
    for (SourceTableColumn sourceColumn : schema.columns()) {
      columns.add(new com.data.collection.platform.entity.database.DatabaseTableColumn(
          sourceColumn.columnName(),
          sourceColumn.columnName(),
          true));
    }
    String defaultSortField = StringUtils.hasText(registry.getUpdatedAtColumn())
        ? registry.getUpdatedAtColumn()
        : schema.primaryKeys().stream().findFirst().orElse(schema.columns().get(0).columnName());
    List<String> searchableFields = schema.columns().stream()
        .map(SourceTableColumn::columnName)
        .filter(this::isSearchableField)
        .limit(6)
        .toList();
    if (searchableFields.isEmpty() && !schema.columns().isEmpty()) {
      searchableFields = List.of(schema.columns().get(0).columnName());
    }
    return new DatabaseBrowserTableDefinition(
        buildSourceLabel(registry),
        searchableFields,
        columns,
        defaultSortField);
  }

  private GitlabMirrorTableRegistry findInitializedRegistry(Long configId, String sourceTableName) {
    GitlabMirrorTableRegistry registry =
        registryMapper.selectOne(
            new LambdaQueryWrapper<GitlabMirrorTableRegistry>()
                .eq(GitlabMirrorTableRegistry::getConfigId, configId)
                .eq(GitlabMirrorTableRegistry::getSourceTableName, sourceTableName)
                .eq(GitlabMirrorTableRegistry::getInitialized, true)
                .last("limit 1"));
    if (registry == null) {
      throw new BizException("当前来源表不在数据库查看白名单中");
    }
    return registry;
  }

  private DatabaseBrowserTableContext resolveTableContext(String tableName) {
    DatabaseBrowserTableDefinition systemDefinition =
        DatabaseBrowserTableCatalog.findDefinition(tableName);
    if (systemDefinition != null) {
      return new DatabaseBrowserTableContext(systemDefinition, null);
    }
    GitlabMirrorTableRegistry registry =
        registryMapper.selectOne(
            new LambdaQueryWrapper<GitlabMirrorTableRegistry>()
                .eq(GitlabMirrorTableRegistry::getMirrorTableName, tableName)
                .eq(GitlabMirrorTableRegistry::getInitialized, true)
                .last("limit 1"));
    if (registry == null) {
      throw new BizException("当前表不在数据库查看白名单中");
    }
    return new DatabaseBrowserTableContext(
        mirrorTableDefinitionFactory.buildMirrorTableDefinition(registry),
        registry);
  }

  private String sourceTableKey(GitlabMirrorTableRegistry registry) {
    return SOURCE_PREFIX + registry.getConfigId() + ":" + registry.getSourceTableName();
  }

  private boolean isSourceTableKey(String tableName) {
    return tableName != null && tableName.startsWith(SOURCE_PREFIX);
  }

  private SourceTableSelection parseSourceTableKey(String tableName) {
    String[] parts = tableName.split(":", 3);
    if (parts.length != 3 || !"source".equals(parts[0])) {
      throw new BizException("来源表标识无效");
    }
    try {
      Long configId = Long.valueOf(parts[1]);
      if (!StringUtils.hasText(parts[2])) {
        throw new BizException("来源表标识无效");
      }
      return new SourceTableSelection(configId, parts[2]);
    } catch (NumberFormatException e) {
      throw new BizException("来源表标识无效");
    }
  }

  private String buildSourceLabel(GitlabMirrorTableRegistry registry) {
    return "来源表 / " + registry.getSourceTableName() + " / config " + registry.getConfigId();
  }

  private boolean isSearchableField(String columnName) {
    return columnName != null && !List.of("metadata", "payload", "description_html").contains(columnName);
  }

  private boolean isSourceEnabled(GitlabSyncConfig config) {
    return config != null && (config.getSourceEnabled() == null ? config.isEnabled() : config.getSourceEnabled());
  }

  private String buildStatusMessage(GitlabMirrorTableRegistry registry) {
    if (registry != null && Objects.equals(registry.getSyncStatus(), "SYNCING")) {
      return "数据正在同步中，当前展示为历史稳定版本。";
    }
    return null;
  }

  private record SourceTableSelection(Long configId, String sourceTable) {
  }
}
