package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.SourceTableSchema;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在动态 ODS 表存在后维护当前查询契约所需索引。 */
@Service
public class GitlabMirrorIndexService {
  private static final Set<String> MIRROR_METADATA_COLUMNS =
      Set.of(
          "mirror_id",
          "mirror_task_id",
          "source_updated_at",
          "mirror_synced_at",
          "mirror_deleted",
          "mirror_created_at",
          "mirror_updated_at");

  private final JdbcTemplate jdbcTemplate;

  public GitlabMirrorIndexService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 为已存在的动态镜像表补齐有效索引。
   *
   * <p>并发索引不能运行在事务块中；该控制面方法显式挂起调用方事务，并只对缺失或失效索引执行 DDL。
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void ensureIndexes(
      String sourceTable, String mirrorTable, SourceTableSchema mirrorSchema) {
    if (mirrorTable == null || mirrorTable.isBlank() || mirrorSchema == null) {
      throw new IllegalArgumentException("动态 ODS 索引准备缺少表名或结构");
    }
    Set<String> availableColumns = new LinkedHashSet<>(MIRROR_METADATA_COLUMNS);
    mirrorSchema.columns().stream()
        .map(column -> column.columnName().toLowerCase(java.util.Locale.ROOT))
        .forEach(availableColumns::add);
    List<GitlabMirrorIndexCatalog.IndexDefinition> applicable =
        GitlabMirrorIndexCatalog.definitionsFor(sourceTable).stream()
            .filter(definition -> availableColumns.containsAll(definition.requiredColumns()))
            .toList();
    if (applicable.isEmpty()) {
      return;
    }

    Map<String, Boolean> current = loadIndexValidity(mirrorTable, applicable);
    for (GitlabMirrorIndexCatalog.IndexDefinition definition : applicable) {
      Boolean valid = current.get(definition.name());
      if (Boolean.TRUE.equals(valid)) {
        continue;
      }
      if (Boolean.FALSE.equals(valid)) {
        jdbcTemplate.execute(
            "drop index concurrently if exists " + quoteIdentifier(definition.name()));
      }
      jdbcTemplate.execute(createIndexSql(mirrorTable, definition));
    }
  }

  private Map<String, Boolean> loadIndexValidity(
      String mirrorTable, List<GitlabMirrorIndexCatalog.IndexDefinition> definitions) {
    String placeholders = String.join(", ", java.util.Collections.nCopies(definitions.size(), "?"));
    String sql =
        """
        select index_class.relname as index_name, index_state.indisvalid
          from pg_index index_state
          join pg_class table_class on table_class.oid = index_state.indrelid
          join pg_namespace table_namespace on table_namespace.oid = table_class.relnamespace
          join pg_class index_class on index_class.oid = index_state.indexrelid
         where table_namespace.nspname = current_schema()
           and table_class.relname = ?
           and index_class.relname in (%s)
        """
            .formatted(placeholders);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>(definitions.size() + 1);
    args.add(mirrorTable);
    definitions.stream().map(GitlabMirrorIndexCatalog.IndexDefinition::name).forEach(args::add);
    return jdbcTemplate.query(
        sql,
        resultSet -> {
          LinkedHashMap<String, Boolean> validity = new LinkedHashMap<>();
          while (resultSet.next()) {
            validity.put(
                resultSet.getString("index_name"), resultSet.getBoolean("indisvalid"));
          }
          return validity;
        },
        args.toArray());
  }

  private String createIndexSql(
      String mirrorTable, GitlabMirrorIndexCatalog.IndexDefinition definition) {
    String columns =
        definition.columns().stream()
            .map(
                column ->
                    quoteIdentifier(column.name()) + (column.descending() ? " desc" : ""))
            .collect(Collectors.joining(", "));
    return "create index concurrently if not exists "
        + quoteIdentifier(definition.name())
        + " on "
        + quoteIdentifier(mirrorTable)
        + " ("
        + columns
        + ") where "
        + definition.predicate();
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
