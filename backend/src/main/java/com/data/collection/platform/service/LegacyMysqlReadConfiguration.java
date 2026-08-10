package com.data.collection.platform.service;

import java.util.List;

/** 老平台 MySQL 的只读连接快照，不包含任何目标表或发布策略。 */
public record LegacyMysqlReadConfiguration(
    List<Source> sources,
    String username,
    String password,
    int fetchSize,
    boolean syncEnabled) {
  public LegacyMysqlReadConfiguration {
    sources = sources == null ? List.of() : List.copyOf(sources);
  }

  /** 一个逻辑来源实例及其只读 JDBC 地址。 */
  public record Source(String sourceInstance, String jdbcUrl) {}
}
