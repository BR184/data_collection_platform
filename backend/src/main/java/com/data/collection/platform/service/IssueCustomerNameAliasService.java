package com.data.collection.platform.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 读取客户名称的精确别名配置，供事实构建时规范化客户成员。 */
@Service
public class IssueCustomerNameAliasService {
  private final JdbcTemplate jdbcTemplate;

  public IssueCustomerNameAliasService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 读取当前所有有效别名。事实构建在一次运行内加载一次，避免每条议题重复访问配置表。
   *
   * @return 原始别名到规范客户名称的不可变映射
   */
  public Map<String, String> loadAliases() {
    List<CustomerNameAlias> rows =
        jdbcTemplate.query(
            """
            select alias_name, canonical_name
              from issue_customer_name_aliases
             where nullif(btrim(alias_name), '') is not null
               and nullif(btrim(canonical_name), '') is not null
             order by alias_name
            """,
            (rs, rowNum) -> new CustomerNameAlias(rs.getString("alias_name"), rs.getString("canonical_name")));
    Map<String, String> aliases = new LinkedHashMap<>();
    for (CustomerNameAlias row : rows) {
      String alias = TextQuerySupport.trimToNull(row.aliasName());
      String canonical = TextQuerySupport.trimToNull(row.canonicalName());
      if (alias != null && canonical != null) {
        aliases.putIfAbsent(alias, canonical);
      }
    }
    return Map.copyOf(aliases);
  }

  private record CustomerNameAlias(String aliasName, String canonicalName) {}
}
