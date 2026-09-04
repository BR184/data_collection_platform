package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 按数据源模式把来源查询路由到 DIRECT JDBC 或 Docker psql 执行器，是两种读取模式的唯一分派点。 */
@Component
class GitlabSourceQueryDispatcher {
  private final GitlabDirectJdbcExecutor directJdbcExecutor;
  private final GitlabDockerPsqlExecutor dockerPsqlExecutor;

  GitlabSourceQueryDispatcher(
      GitlabDirectJdbcExecutor directJdbcExecutor,
      GitlabDockerPsqlExecutor dockerPsqlExecutor) {
    this.directJdbcExecutor = directJdbcExecutor;
    this.dockerPsqlExecutor = dockerPsqlExecutor;
  }

  /** 测试指定配置的来源连通性；空模式按历史默认回落到 DOCKER。 */
  void testConnection(GitlabSyncConfig config) {
    if (isDirect(config)) {
      directJdbcExecutor.testConnection(config);
      return;
    }
    dockerPsqlExecutor.testConnection(config);
  }

  /** 执行普通 SQL 并返回解析后的行集合。 */
  List<Map<String, Object>> query(GitlabSyncConfig config, String sql) {
    if (isDirect(config)) {
      return directJdbcExecutor.query(config, sql);
    }
    return dockerPsqlExecutor.queryRows(config, sql);
  }

  /** 执行参数化查询（仅 DIRECT 支持）。 */
  List<Map<String, Object>> query(GitlabSyncConfig config, GitlabParameterizedQuery query) {
    return directJdbcExecutor.query(config, query);
  }

  /** 通过 Docker COPY 会话执行多范围/批量脚本并解析行集合。 */
  List<Map<String, Object>> scriptQuery(GitlabSyncConfig config, String script) {
    return dockerPsqlExecutor.queryScriptRows(config, script);
  }

  private boolean isDirect(GitlabSyncConfig config) {
    return config != null && config.getSourceMode() == SourceMode.DIRECT;
  }

  static BizException unsupportedSourceMode(SourceMode sourceMode) {
    return new BizException("不支持的 GitLab 数据源模式：" + sourceMode);
  }
}
