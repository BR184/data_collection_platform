package com.data.collection.platform.service;

import java.util.List;

/** GitLab DIRECT 模式下带有序绑定参数的只读查询。 */
record GitlabParameterizedQuery(String sql, List<Object> parameters) {
  GitlabParameterizedQuery {
    if (sql == null || sql.isBlank()) {
      throw new IllegalArgumentException("GitLab 参数查询 SQL 不能为空");
    }
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
  }
}
