package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.DatasetReadSelection;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.ReadSelection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;

/** 动态规则的参数化只读 SQL：原 DSL 参数与固定来源 CTE 共用实际数据库类型，不能读所有历史修订。 */
@Service
public class VersionedNamedReadSql {
  private final JdbcTemplate jdbc;

  public VersionedNamedReadSql(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** 在调用者持有完整读取锁的事务内查询；不接受当前原表替补缺失的版本输入。 */
  public <T> List<T> query(ReadSelection selection, String sql, SqlParameterSource parameters, RowMapper<T> mapper) {
    var parsed = NamedParameterUtils.parseSqlStatement(sql);
    String positional = NamedParameterUtils.substituteNamedParameters(parsed, parameters);
    List<Object> arguments = new ArrayList<>();
    for (Object value : NamedParameterUtils.buildValueArray(parsed, parameters, null)) {
      Object actual = value instanceof org.springframework.jdbc.core.SqlParameterValue typed ? typed.getValue() : value;
      if (actual instanceof Iterable<?> collection) {
        for (Object item : collection) {
          if (item instanceof Object[] tuple) arguments.addAll(java.util.Arrays.asList(tuple));
          else arguments.add(item);
        }
      } else arguments.add(value);
    }
    VersionedFactSql.Query bound = selection instanceof DatasetReadSelection dataset
        ? new VersionedDatasetSql(jdbc).bind(dataset, positional, arguments)
        : VersionedFactSql.bind((FactReadSelection) selection, positional, arguments);
    return jdbc.query(bound.sql(), mapper, bound.arguments().toArray());
  }
}
