package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.SqlIdentifierSupport;
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

    Map<String, IndexState> current = loadIndexStates(applicable);
    for (GitlabMirrorIndexCatalog.IndexDefinition definition : applicable) {
      IndexState state = current.get(definition.name());
      if (state != null && state.valid()) {
        requireSameDefinition(mirrorTable, definition, state);
        continue;
      }
      if (state != null) {
        jdbcTemplate.execute(
            "drop index concurrently if exists " + SqlIdentifierSupport.quoteIdentifier(definition.name()));
      }
      jdbcTemplate.execute(createIndexSql(mirrorTable, definition));
    }
  }

  /**
   * 同名有效索引必须与目录声明的表、方法、键列顺序/方向和谓词一致才能复用。
   *
   * <p>索引名不是契约：现场已有同名但异义的索引时明确报错并保留现场索引，不能因为“有效”就跳过，
   * 否则目标查询仍可能缺少可用索引。
   */
  private void requireSameDefinition(
      String mirrorTable,
      GitlabMirrorIndexCatalog.IndexDefinition definition,
      IndexState state) {
    List<String> expectedColumns =
        definition.columns().stream().map(GitlabMirrorIndexCatalog.IndexColumn::name).toList();
    List<Boolean> expectedDescending =
        definition.columns().stream().map(GitlabMirrorIndexCatalog.IndexColumn::descending).toList();
    boolean same =
        mirrorTable.equalsIgnoreCase(state.tableName())
            && "btree".equalsIgnoreCase(state.method())
            && expectedColumns.equals(state.columns())
            && expectedDescending.equals(state.descending())
            && predicateTerms(definition.predicate()).equals(predicateTerms(state.predicate()));
    if (!same) {
      throw new IllegalStateException(
          "同名索引定义与目录不一致，保留现场索引："
              + definition.name()
              + "（实际表="
              + state.tableName()
              + "，方法="
              + state.method()
              + "，列="
              + state.columns()
              + "，方向="
              + state.descending()
              + "，谓词="
              + state.predicate()
              + "；目录列="
              + expectedColumns
              + "，方向="
              + expectedDescending
              + "，谓词="
              + definition.predicate()
              + "）");
    }
  }

  private Map<String, IndexState> loadIndexStates(
      List<GitlabMirrorIndexCatalog.IndexDefinition> definitions) {
    String placeholders = String.join(", ", java.util.Collections.nCopies(definitions.size(), "?"));
    String sql =
        """
        select index_class.relname as index_name, table_class.relname as table_name,
               index_state.indisvalid, am.amname as index_method,
               pg_get_expr(index_state.indpred, index_state.indrelid) as predicate,
               array(
                 select att.attname
                   from generate_series(1, index_state.indnkeyatts) as pos(k)
                   join pg_attribute att on att.attrelid = index_state.indrelid
                                      and att.attnum = index_state.indkey[pos.k - 1]
                  order by pos.k
               ) as key_columns,
               array(
                 select case when (index_state.indoption[pos.k - 1] & 1) = 1 then 1 else 0 end
                   from generate_series(1, index_state.indnkeyatts) as pos(k)
                  order by pos.k
               ) as key_descending
          from pg_index index_state
          join pg_class table_class on table_class.oid = index_state.indrelid
          join pg_namespace table_namespace on table_namespace.oid = table_class.relnamespace
          join pg_class index_class on index_class.oid = index_state.indexrelid
          join pg_am am on am.oid = index_class.relam
         where table_namespace.nspname = current_schema()
           and index_class.relname in (%s)
        """
            .formatted(placeholders);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>(definitions.size());
    definitions.stream().map(GitlabMirrorIndexCatalog.IndexDefinition::name).forEach(args::add);
    return jdbcTemplate.query(
        sql,
        resultSet -> {
          LinkedHashMap<String, IndexState> states = new LinkedHashMap<>();
          while (resultSet.next()) {
            java.sql.Array columnArray = resultSet.getArray("key_columns");
            java.sql.Array descendingArray = resultSet.getArray("key_descending");
            List<String> columns = new java.util.ArrayList<>();
            if (columnArray != null) {
              for (Object column : (Object[]) columnArray.getArray()) {
                columns.add(String.valueOf(column));
              }
            }
            List<Boolean> descending = new java.util.ArrayList<>();
            if (descendingArray != null) {
              for (Object value : (Object[]) descendingArray.getArray()) {
                descending.add(value instanceof Number number && number.intValue() == 1);
              }
            }
            states.put(
                resultSet.getString("index_name"),
                new IndexState(
                    resultSet.getString("table_name"),
                    resultSet.getBoolean("indisvalid"),
                    resultSet.getString("index_method"),
                    List.copyOf(columns),
                    List.copyOf(descending),
                    resultSet.getString("predicate")));
          }
          return states;
        },
        args.toArray());
  }

  /** 谓词按顶层 AND 拆项并忽略括号与空白差异，保留每一项内部的逻辑分组。 */
  static List<String> predicateTerms(String predicate) {
    if (predicate == null || predicate.isBlank()) {
      return List.of();
    }
    String value = stripWrappingParentheses(predicate.trim().toLowerCase(java.util.Locale.ROOT));
    List<String> terms = new java.util.ArrayList<>();
    int depth = 0;
    int start = 0;
    int index = 0;
    while (index < value.length()) {
      char current = value.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
      } else if (depth == 0 && value.startsWith(" and ", index)) {
        terms.add(normalizeTerm(value.substring(start, index)));
        index += 5;
        start = index;
        continue;
      }
      index++;
    }
    terms.add(normalizeTerm(value.substring(start)));
    return terms.stream().sorted().toList();
  }

  private static String normalizeTerm(String term) {
    return stripWrappingParentheses(term.replaceAll("\\s+", " ").trim());
  }

  private static String stripWrappingParentheses(String value) {
    String result = value.trim();
    while (enclosesWholeExpression(result)) {
      result = result.substring(1, result.length() - 1).trim();
    }
    return result;
  }

  private static boolean enclosesWholeExpression(String value) {
    if (!value.startsWith("(") || !value.endsWith(")")) {
      return false;
    }
    int depth = 0;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
        if (depth == 0 && index != value.length() - 1) {
          return false;
        }
      }
    }
    return depth == 0;
  }

  private record IndexState(
      String tableName,
      boolean valid,
      String method,
      List<String> columns,
      List<Boolean> descending,
      String predicate) {}

  private String createIndexSql(
      String mirrorTable, GitlabMirrorIndexCatalog.IndexDefinition definition) {
    String columns =
        definition.columns().stream()
            .map(
                column ->
                    SqlIdentifierSupport.quoteIdentifier(column.name()) + (column.descending() ? " desc" : ""))
            .collect(Collectors.joining(", "));
    String sql =
        "create index concurrently if not exists "
            + SqlIdentifierSupport.quoteIdentifier(definition.name())
            + " on "
            + SqlIdentifierSupport.quoteIdentifier(mirrorTable)
            + " ("
            + columns
            + ")";
    return definition.predicate() == null ? sql : sql + " where " + definition.predicate();
  }

}
