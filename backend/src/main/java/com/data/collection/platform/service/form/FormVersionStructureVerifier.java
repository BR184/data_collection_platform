package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormVersionColumn;
import com.data.collection.platform.domain.form.FormVersionConstraint;
import com.data.collection.platform.domain.form.FormVersionIndex;
import com.data.collection.platform.domain.form.FormVersionSchema;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 冻结版本结构的完整契约核验。
 *
 * <p>启动核验、发布恢复与复制前检查共用同一实现：列名/类型/可空性、约束集合与CHECK字面量、
 * 主键/唯一/外键及动作、索引定义与记录ID默认序列。任一差异即判定漂移；调用方不得继续
 * truncate、切指针或宣告发布成功。
 */
@Component
public class FormVersionStructureVerifier {

  private final JdbcTemplate jdbcTemplate;

  public FormVersionStructureVerifier(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 核验两张版本表与冻结结构完全一致。
   *
   * @param expected 由冻结 field_spec 生成的确定性结构
   * @throws IllegalStateException 任一项漂移；消息包含表名与对象名
   */
  public void verify(FormVersionSchema expected) {
    Map<String, Map<String, ActualColumn>> actual =
        actualColumns(expected.instanceTable(), expected.recordTable());
    compareColumns(expected, actual);
    validateConstraints(expected.instanceTable(), expected.constraints());
    validateConstraints(expected.recordTable(), expected.constraints());
    validateIndexes(expected);
    validateRecordIdDefault(expected);
  }

  private Map<String, Map<String, ActualColumn>> actualColumns(
      String instanceTable, String recordTable) {
    Map<String, Map<String, ActualColumn>> result = new LinkedHashMap<>();
    result.put(instanceTable, new LinkedHashMap<>());
    result.put(recordTable, new LinkedHashMap<>());
    jdbcTemplate.query(
        """
        select table_name, column_name, data_type, character_maximum_length, udt_name, is_nullable
          from information_schema.columns
         where table_schema = current_schema()
           and table_name in (?, ?)
        """,
        rs -> {
          String table = rs.getString("table_name");
          Map<String, ActualColumn> columns = result.get(table);
          if (columns != null) {
            columns.put(
                rs.getString("column_name"),
                new ActualColumn(
                    normalizedType(
                        rs.getString("data_type"),
                        rs.getObject("character_maximum_length", Integer.class),
                        rs.getString("udt_name")),
                    "YES".equals(rs.getString("is_nullable"))));
          }
        },
        instanceTable,
        recordTable);
    return result;
  }

  private void compareColumns(
      FormVersionSchema expected, Map<String, Map<String, ActualColumn>> actual) {
    for (FormVersionColumn column : expected.columns()) {
      ActualColumn found =
          actual.getOrDefault(column.tableName(), Map.of()).get(column.columnName());
      if (found == null) {
        throw new IllegalStateException(
            "结构漂移：缺少列 " + column.tableName() + "." + column.columnName());
      }
      if (!found.type().equals(column.columnType())) {
        throw new IllegalStateException(
            "结构漂移：列类型不一致 "
                + column.tableName()
                + "."
                + column.columnName()
                + " expected="
                + column.columnType()
                + " actual="
                + found.type());
      }
      if (found.nullable() != column.nullable()) {
        throw new IllegalStateException(
            "结构漂移：列可空性不一致 " + column.tableName() + "." + column.columnName());
      }
    }
    Set<String> expectedInstanceColumns =
        expected.columns().stream()
            .filter(column -> column.tableName().equals(expected.instanceTable()))
            .map(FormVersionColumn::columnName)
            .collect(java.util.stream.Collectors.toSet());
    Set<String> expectedRecordColumns =
        expected.columns().stream()
            .filter(column -> column.tableName().equals(expected.recordTable()))
            .map(FormVersionColumn::columnName)
            .collect(java.util.stream.Collectors.toSet());
    if (!new HashSet<>(actual.getOrDefault(expected.instanceTable(), Map.of()).keySet())
        .equals(expectedInstanceColumns)) {
      throw new IllegalStateException("结构漂移：实例表存在未登记列");
    }
    if (!new HashSet<>(actual.getOrDefault(expected.recordTable(), Map.of()).keySet())
        .equals(expectedRecordColumns)) {
      throw new IllegalStateException("结构漂移：记录表存在未登记列");
    }
  }

  private void validateConstraints(String tableName, List<FormVersionConstraint> allExpected) {
    List<FormVersionConstraint> expected =
        allExpected.stream()
            .filter(constraint -> constraint.tableName().equals(tableName))
            .toList();
    Map<String, ActualConstraint> actual = actualConstraints(tableName);
    Set<String> expectedNames =
        expected.stream()
            .map(FormVersionConstraint::constraintName)
            .collect(java.util.stream.Collectors.toSet());
    if (!actual.keySet().equals(expectedNames)) {
      throw new IllegalStateException(
          "结构漂移：约束集合不一致 table="
              + tableName
              + " expected="
              + expectedNames
              + " actual="
              + actual.keySet());
    }
    Map<String, String> canonicalChecks = Map.of();
    List<FormVersionConstraint> checks =
        expected.stream()
            .filter(constraint -> constraint.kind() == FormVersionConstraint.Kind.CHECK)
            .toList();
    if (!checks.isEmpty()) {
      canonicalChecks = canonicalCheckExpressions(tableName, checks);
    }
    for (FormVersionConstraint constraint : expected) {
      ActualConstraint found = actual.get(constraint.constraintName());
      if (!Objects.equals(found.kind(), constraint.kind())) {
        throw new IllegalStateException(
            "结构漂移：约束类型不一致 " + tableName + "." + constraint.constraintName());
      }
      if (constraint.kind() == FormVersionConstraint.Kind.CHECK) {
        String expectedExpression = canonicalChecks.get(constraint.constraintName());
        if (!expectedExpression.equals(found.expression())) {
          throw new IllegalStateException(
              "结构漂移：检查约束定义不一致 "
                  + tableName
                  + "."
                  + constraint.constraintName());
        }
      } else {
        if (!constraint.columns().equals(found.columns())) {
          throw new IllegalStateException(
              "结构漂移：约束列不一致 " + tableName + "." + constraint.constraintName());
        }
        if (constraint.kind() == FormVersionConstraint.Kind.FOREIGN_KEY) {
          if (!Objects.equals(constraint.referenceTable(), found.referenceTable())
              || !constraint.referenceColumns().equals(found.referenceColumns())) {
            throw new IllegalStateException(
                "结构漂移：外键引用不一致 " + tableName + "." + constraint.constraintName());
          }
          if (!Objects.equals(constraint.onDelete(), found.onDelete())
              || !Objects.equals(constraint.onUpdate(), found.onUpdate())
              || !Objects.equals(constraint.matchType(), found.matchType())) {
            throw new IllegalStateException(
                "结构漂移：外键执行属性不一致 "
                    + tableName
                    + "."
                    + constraint.constraintName());
          }
        }
      }
      if (constraint.deferrable() != found.deferrable()
          || constraint.initiallyDeferred() != found.initiallyDeferred()
          || constraint.validated() != found.validated()) {
        throw new IllegalStateException(
            "结构漂移：约束执行属性不一致 " + tableName + "." + constraint.constraintName());
      }
    }
  }

  /**
   * 由 PostgreSQL 解析并反解析冻结表达式，保留逻辑分组与字面量语义。
   *
   * <p>临时表是会话级对象，创建、ALTER、读取与清理必须在同一个物理连接上完成；连接池不保证
   * 多次 JdbcTemplate 调用落到同一会话，因此这里用单一 ConnectionCallback 承载完整生命周期。
   */
  private Map<String, String> canonicalCheckExpressions(
      String tableName, List<FormVersionConstraint> checks) {
    return jdbcTemplate.execute(
        (ConnectionCallback<Map<String, String>>)
            connection -> {
              String probeTable =
                  "form_ck_probe_" + UUID.randomUUID().toString().replace("-", "");
              try (Statement statement = connection.createStatement()) {
                statement.execute(
                    "create temp table " + probeTable + " (like " + tableName + ")");
              } catch (SQLException error) {
                throw new IllegalStateException(
                    "无法创建冻结约束校验临时表: " + tableName, error);
              }
              try {
                Map<String, String> canonical = new LinkedHashMap<>();
                int index = 1;
                for (FormVersionConstraint check : checks) {
                  String probeConstraint = "ck_probe_" + index++;
                  try (Statement statement = connection.createStatement()) {
                    statement.execute(
                        "alter table "
                            + probeTable
                            + " add constraint "
                            + probeConstraint
                            + " check ("
                            + check.expression()
                            + ")");
                  } catch (SQLException error) {
                    throw new IllegalStateException(
                        "冻结检查约束表达式无法由 PostgreSQL 解析: " + check.expression(), error);
                  }
                  try (PreparedStatement query =
                      connection.prepareStatement(
                          """
                          select pg_get_expr(conbin, conrelid, true)
                            from pg_constraint
                           where conrelid = to_regclass(?) and conname = ?
                          """)) {
                    query.setString(1, probeTable);
                    query.setString(2, probeConstraint);
                    try (ResultSet result = query.executeQuery()) {
                      if (!result.next()) {
                        throw new IllegalStateException(
                            "无法读取冻结检查约束的反解析结果: " + check.expression());
                      }
                      canonical.put(check.constraintName(), result.getString(1));
                    }
                  }
                  try (Statement statement = connection.createStatement()) {
                    statement.execute(
                        "alter table "
                            + probeTable
                            + " drop constraint if exists "
                            + probeConstraint);
                  }
                }
                return canonical;
              } finally {
                try (Statement statement = connection.createStatement()) {
                  statement.execute("drop table if exists " + probeTable);
                }
              }
            });
  }

  private Map<String, ActualConstraint> actualConstraints(String tableName) {
    Map<String, ActualConstraint> constraints = new LinkedHashMap<>();
    jdbcTemplate.query(
        """
        select c.conname, c.contype,
               pg_get_expr(c.conbin, c.conrelid, true) as expression,
               c.confdeltype, c.confupdtype, c.confmatchtype,
               c.condeferrable, c.condeferred, c.convalidated,
               coalesce((select string_agg(a.attname, ',' order by k.ord)
                           from unnest(c.conkey) with ordinality k(attnum, ord)
                           join pg_attribute a
                             on a.attrelid = c.conrelid and a.attnum = k.attnum), '') as columns,
               coalesce(cf.relname, '') as reference_table,
               coalesce((select string_agg(a.attname, ',' order by k.ord)
                           from unnest(c.confkey) with ordinality k(attnum, ord)
                           join pg_attribute a
                             on a.attrelid = c.confrelid and a.attnum = k.attnum), '') as reference_columns
          from pg_constraint c
          join pg_class t on t.oid = c.conrelid
          join pg_namespace n on n.oid = t.relnamespace
          left join pg_class cf on cf.oid = c.confrelid
         where n.nspname = current_schema() and t.relname = ?
        """,
        rs -> {
          constraints.put(
              rs.getString("conname"),
              new ActualConstraint(
                  switch (rs.getString("contype")) {
                    case "p" -> FormVersionConstraint.Kind.PRIMARY_KEY;
                    case "u" -> FormVersionConstraint.Kind.UNIQUE;
                    case "f" -> FormVersionConstraint.Kind.FOREIGN_KEY;
                    case "c" -> FormVersionConstraint.Kind.CHECK;
                    default -> null;
                  },
                  splitColumns(rs.getString("columns")),
                  emptyToNull(rs.getString("reference_table")),
                  splitColumns(rs.getString("reference_columns")),
                  rs.getString("expression"),
                  foreignKeyAction(rs.getString("confdeltype")),
                  foreignKeyAction(rs.getString("confupdtype")),
                  foreignKeyMatchType(rs.getString("confmatchtype")),
                  rs.getBoolean("condeferrable"),
                  rs.getBoolean("condeferred"),
                  rs.getBoolean("convalidated")));
        },
        tableName);
    return constraints;
  }

  private String foreignKeyAction(String code) {
    if (code == null) {
      return null;
    }
    return switch (code) {
      case "a" -> "NO ACTION";
      case "r" -> "RESTRICT";
      case "c" -> "CASCADE";
      case "n" -> "SET NULL";
      case "d" -> "SET DEFAULT";
      default -> null;
    };
  }

  private String foreignKeyMatchType(String code) {
    if (code == null) {
      return null;
    }
    return switch (code) {
      case "s" -> "SIMPLE";
      case "f" -> "FULL";
      case "p" -> "PARTIAL";
      default -> null;
    };
  }

  private List<String> splitColumns(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(raw.split(",")).toList();
  }

  private String emptyToNull(String raw) {
    return raw == null || raw.isBlank() ? null : raw;
  }

  private void validateIndexes(FormVersionSchema expected) {
    for (FormVersionIndex index : expected.indexes()) {
      List<String> definitions =
          jdbcTemplate.queryForList(
              """
              select indexdef from pg_indexes
               where schemaname = current_schema() and tablename = ? and indexname = ?
              """,
              String.class,
              index.tableName(),
              index.indexName());
      if (definitions.isEmpty()) {
        throw new IllegalStateException(
            "结构漂移：缺少索引 " + index.tableName() + "." + index.indexName());
      }
      if (!normalizeIndex(definitions.get(0)).equals(normalizeIndex(index.definition()))) {
        throw new IllegalStateException(
            "结构漂移：索引定义不一致 " + index.tableName() + "." + index.indexName());
      }
    }
  }

  private void validateRecordIdDefault(FormVersionSchema expected) {
    List<String> defaults =
        jdbcTemplate.queryForList(
            """
            select column_default from information_schema.columns
             where table_schema = current_schema() and table_name = ? and column_name = 'id'
            """,
            String.class,
            expected.recordTable());
    if (defaults.isEmpty()
        || defaults.get(0) == null
        || !normalizeDefault(defaults.get(0)).equals(normalizeDefault(expected.recordIdDefault()))) {
      throw new IllegalStateException(
          "结构漂移：记录ID默认序列不一致 " + expected.recordTable() + ".id");
    }
  }

  private String normalizeIndex(String definition) {
    return definition
        .toLowerCase(java.util.Locale.ROOT)
        .replace("public.", "")
        .replace("\"", "")
        .replace("using btree", "")
        .replaceAll("[()\\s]", "");
  }

  private String normalizeDefault(String definition) {
    return definition
        .toLowerCase(java.util.Locale.ROOT)
        .replace("public.", "")
        .replaceAll("\\s+", "");
  }

  private String normalizedType(String dataType, Integer length, String udtName) {
    if ("character varying".equals(dataType)) {
      return "varchar(" + length + ")";
    }
    if ("character".equals(dataType)) {
      return "char(" + length + ")";
    }
    if ("ARRAY".equals(dataType) && "_text".equals(udtName)) {
      return "text[]";
    }
    if ("timestamp without time zone".equals(dataType)) {
      return "timestamp";
    }
    return dataType;
  }

  private record ActualColumn(String type, boolean nullable) {}

  private record ActualConstraint(
      FormVersionConstraint.Kind kind,
      List<String> columns,
      String referenceTable,
      List<String> referenceColumns,
      String expression,
      String onDelete,
      String onUpdate,
      String matchType,
      boolean deferrable,
      boolean initiallyDeferred,
      boolean validated) {}
}
