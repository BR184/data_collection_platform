package com.data.collection.platform.mapper;

import com.data.collection.platform.entity.read.DatasetReadSelection;
import com.data.collection.platform.service.read.VersionedDatasetSql;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Repository;

/** 遗留通用镜像关系的原口径查询，所有表、统计和明细显式绑定同一完整本地数据集。 */
@Repository
public class MirrorTableOverviewMapper {
  private final VersionedDatasetSql sql;
  private final DatasetReadSelection selection;

  @org.springframework.beans.factory.annotation.Autowired
  public MirrorTableOverviewMapper(VersionedDatasetSql sql) { this.sql = sql; this.selection = null; }
  private MirrorTableOverviewMapper(MirrorTableOverviewMapper factory, DatasetReadSelection selection) {
    this.sql = factory.sql; this.selection = java.util.Objects.requireNonNull(selection);
  }

  /** 创建单次读取持有的不可变查询对象，工厂不直接查询业务数据。 */
  public MirrorTableOverviewMapper forRead(DatasetReadSelection selection) { return new MirrorTableOverviewMapper(this, selection); }

  /** 候选表名来自已发布的原 gitlab_mirror_records 关系，保持原计数/排序口径。 */
  public List<String> selectTableNames() {
    return sql.query(readSelection(), "select table_name from gitlab_mirror_records group by table_name order by count(*) desc, table_name asc",
        (rs, row) -> rs.getString(1));
  }

  /** 在固定关系上执行原摘要公式，不改为动态 ODS 表统计。 */
  public List<Map<String, Object>> selectSummaryRows(String tableName) {
    var parameters = new java.util.HashMap<String, Object>(); parameters.put("tableName", tableName);
    return query(SqlProvider.buildSummaryRowsSql(parameters), parameters);
  }

  /** 固定完整版本中的下钻计数，与明细使用相同区间和谓词。 */
  public Long countDetails(String tableName, String columnKey) {
    Map<String, Object> parameters = Map.of("tableName", tableName, "columnKey", columnKey);
    var bound = bind(SqlProvider.buildCountDetailsSql(parameters), parameters);
    return sql.queryForObject(readSelection(), bound.sql(), Long.class, bound.arguments().toArray());
  }

  /** 原明细列、分页与经业务层白名单核验的排序，均在固定关系中执行。 */
  public List<Map<String, Object>> selectDetails(String tableName, String columnKey, String sortField,
      String sortOrder, int size, int offset) {
    Map<String, Object> parameters = Map.of("tableName", tableName, "columnKey", columnKey, "sortField", sortField,
        "sortOrder", sortOrder, "size", size, "offset", offset);
    return query(SqlProvider.buildSelectDetailsSql(parameters), parameters);
  }

  private DatasetReadSelection readSelection() { return java.util.Objects.requireNonNull(selection, "镜像概要必须持有完整关系身份"); }
  private List<Map<String, Object>> query(String query, Map<String, Object> parameters) {
    var bound = bind(query, parameters);
    return sql.query(readSelection(), bound.sql(), new ColumnMapRowMapper(), bound.arguments().toArray());
  }
  private BoundQuery bind(String query, Map<String, Object> parameters) {
    var matcher = java.util.regex.Pattern.compile("#\\{(\\w+)\\}").matcher(query);
    var arguments = new java.util.ArrayList<Object>();
    var result = new StringBuilder();
    while (matcher.find()) {
      String key = matcher.group(1);
      if (!parameters.containsKey(key)) throw new IllegalArgumentException("镜像查询缺少已登记参数：" + key);
      arguments.add(parameters.get(key)); matcher.appendReplacement(result, "?");
    }
    matcher.appendTail(result);
    return new BoundQuery(result.toString(), arguments);
  }
  private record BoundQuery(String sql, List<Object> arguments) {}

  static final class SqlProvider {
    private SqlProvider() {
    }

    public static String buildSummaryRowsSql(Map<String, Object> params) {
      String tableName = (String) params.get("tableName");
      StringBuilder sql = new StringBuilder("""
          select
            table_name as "tableName",
            count(*) as "totalRecords",
            count(updated_at_source) as "withSourceUpdate",
            count(*) - count(updated_at_source) as "withoutSourceUpdate",
            count(*) filter (where synced_at >= current_timestamp - interval '24 hours') as "syncedIn24h",
            count(*) filter (where synced_at < current_timestamp - interval '24 hours') as "staleSync",
            max(synced_at) as "lastSyncedAt",
            max(updated_at_source) as "lastSourceUpdatedAt"
          from gitlab_mirror_records
          where 1 = 1
          """);
      if (tableName != null && !tableName.isBlank()) {
        sql.append(" and table_name = #{tableName} ");
      }
      sql.append("""
          group by table_name
          order by count(*) desc, table_name asc
          """);
      return sql.toString();
    }

    public static String buildCountDetailsSql(Map<String, Object> params) {
      return new StringBuilder("select count(*) from gitlab_mirror_records where table_name = #{tableName} ")
          .append(detailWhereClause((String) params.get("columnKey")))
          .toString();
    }

    public static String buildSelectDetailsSql(Map<String, Object> params) {
      return new StringBuilder("""
          select
            id as "id",
            table_name as "tableName",
            record_key as "recordKey",
            updated_at_source as "updatedAtSource",
            synced_at as "syncedAt",
            row_data::text as "rowData"
          from gitlab_mirror_records
          where table_name = #{tableName}
          """)
          .append(detailWhereClause((String) params.get("columnKey")))
          .append(" order by ")
          .append(params.get("sortField"))
          .append(" ")
          .append(params.get("sortOrder"))
          .append(" nulls last limit #{size} offset #{offset}")
          .toString();
    }

    private static String detailWhereClause(String columnKey) {
      return switch (columnKey) {
        case "totalRecords" -> "";
        case "withSourceUpdate" -> " and updated_at_source is not null ";
        case "withoutSourceUpdate" -> " and updated_at_source is null ";
        case "syncedIn24h" -> " and synced_at >= current_timestamp - interval '24 hours' ";
        case "staleSync" -> " and synced_at < current_timestamp - interval '24 hours' ";
        default -> throw new IllegalArgumentException("Unsupported statistic column: " + columnKey);
      };
    }
  }
}
