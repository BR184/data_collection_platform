package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.database.DatabaseTableOption;
import com.data.collection.platform.entity.database.DatabaseTableRowsResponse;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
// 数据库浏览服务只暴露白名单内的业务表和镜像表，用于运维排查而不是任意 SQL 控制台。
// 表定义负责限制可查列、可排序字段和关键词范围，避免前端直接拼接表结构。
public class DatabaseBrowserService {

  private final JdbcTemplate jdbcTemplate;
  private final GitlabMirrorTableRegistryMapper registryMapper;
  private final DatabaseBrowserMirrorTableDefinitionFactory mirrorTableDefinitionFactory;
  private final GitlabMirrorSyncService gitlabMirrorSyncService;

  public DatabaseBrowserService(
      JdbcTemplate jdbcTemplate,
      GitlabMirrorTableRegistryMapper registryMapper,
      DatabaseBrowserMirrorTableDefinitionFactory mirrorTableDefinitionFactory,
      GitlabMirrorSyncService gitlabMirrorSyncService) {
    this.jdbcTemplate = jdbcTemplate;
    this.registryMapper = registryMapper;
    this.mirrorTableDefinitionFactory = mirrorTableDefinitionFactory;
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
  }

  public List<DatabaseTableOption> listTables() {
    Map<String, DatabaseTableOption> allTables = new LinkedHashMap<>();
    DatabaseBrowserTableCatalog.listDefinitions()
        .forEach(
            (tableName, definition) ->
                allTables.put(
                    tableName,
                    new DatabaseTableOption(tableName, definition.label(), "IDLE", null)));
    listMirrorRegistries()
        .forEach(
            registry ->
                allTables.put(
                    registry.getMirrorTableName(),
                    new DatabaseTableOption(
                        registry.getMirrorTableName(),
                        mirrorTableDefinitionFactory.buildMirrorLabel(registry.getSourceTableName()),
                        registry.getSyncStatus(),
                        registry.getLastSyncTime())));
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
        buildStatusMessage(context.registry()));
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

  private String buildStatusMessage(GitlabMirrorTableRegistry registry) {
    if (registry != null && Objects.equals(registry.getSyncStatus(), "SYNCING")) {
      return "数据正在同步中，当前展示为历史稳定版本。";
    }
    return null;
  }
}
