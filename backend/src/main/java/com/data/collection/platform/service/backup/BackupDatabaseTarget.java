package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 备份目标数据库的连接参数，从 spring.datasource 配置解析；
 * pg_dump 以普通网络客户端连接同一数据库，不感知容器与部署形态。
 */
public record BackupDatabaseTarget(String host, int port, String database, String username, String password) {
  private static final Pattern JDBC_URL =
      Pattern.compile("^jdbc:postgresql://([^/:?]+)(?::(\\d+))?/([^?]+).*$");

  public static BackupDatabaseTarget from(String jdbcUrl, String username, String password) {
    Matcher matcher = JDBC_URL.matcher(jdbcUrl == null ? "" : jdbcUrl.trim());
    if (!matcher.matches()) {
      throw new BizException("DATASOURCE_URL 不是可解析的 PostgreSQL JDBC 地址，无法确定备份目标");
    }
    return new BackupDatabaseTarget(
        matcher.group(1),
        matcher.group(2) == null ? 5432 : Integer.parseInt(matcher.group(2)),
        matcher.group(3),
        username,
        password);
  }
}
