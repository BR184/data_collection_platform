package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabExternalDbServiceTest {

  private GitlabSourceScanSqlBuilder scanSqlBuilder;
  private GitlabPrimaryKeyExistenceQueryBuilder primaryKeyQueryBuilder;
  private GitlabAuthoritativeScopeQueryBuilder authoritativeScopeQueryBuilder;
  private GitlabDirectJdbcExecutor directJdbcExecutor;
  private GitlabDockerPsqlExecutor dockerPsqlExecutor;
  private GitlabSourceQueryDispatcher queryDispatcher;
  private GitlabSourceSchemaDiscoveryService schemaDiscoveryService;
  private GitlabSourceMetadataSupport metadataSupport;
  private GitlabExternalDbService service;

  @BeforeEach
  void setUp() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    scanSqlBuilder = new GitlabSourceScanSqlBuilder(new com.data.collection.platform.common.JsonUtils(new com.fasterxml.jackson.databind.ObjectMapper()));
    primaryKeyQueryBuilder = new GitlabPrimaryKeyExistenceQueryBuilder();
    authoritativeScopeQueryBuilder = new GitlabAuthoritativeScopeQueryBuilder();
    directJdbcExecutor = new GitlabDirectJdbcExecutor(
        new GitlabSourceConnectionSettings(properties),
        new GitlabSourceQueryRetryPolicy(properties),
        new GitlabJdbcValueNormalizer(),
        new com.data.collection.platform.service.sync.SyncThreadBudgetResolver(properties));
    dockerPsqlExecutor = new GitlabDockerPsqlExecutor(
        properties,
        new GitlabSourceConnectionSettings(properties),
        new GitlabSourceQueryRetryPolicy(properties),
        new com.fasterxml.jackson.databind.ObjectMapper());
    queryDispatcher = new GitlabSourceQueryDispatcher(directJdbcExecutor, dockerPsqlExecutor);
    metadataSupport = new GitlabSourceMetadataSupport();
    schemaDiscoveryService =
        new GitlabSourceSchemaDiscoveryService(queryDispatcher::query, metadataSupport);
    service = new GitlabExternalDbService(
        scanSqlBuilder,
        primaryKeyQueryBuilder,
        authoritativeScopeQueryBuilder,
        directJdbcExecutor,
        queryDispatcher,
        schemaDiscoveryService,
        metadataSupport);
  }

  @Test
  void incrementalScanShouldReturnEmptyWithoutUpdatedAtColumn() {
    TableWhitelistOption option =
        new TableWhitelistOption(
            "label_links", "Label links", "id", null, SourceCursorStrategy.NONE, true);

    assertThat(service.incrementalScan(null, option, LocalDateTime.of(2026, 1, 1, 0, 0))).isEmpty();
  }

  @Test
  void extractUpdatedAtShouldReturnNullForBlankUpdatedAtColumn() {
    TableWhitelistOption option =
        new TableWhitelistOption(
            "issues", "Issues", "id", "", SourceCursorStrategy.PRIMARY_KEY_KEYSET, true);

    assertThat(service.extractUpdatedAt(option, Map.of("updated_at", "2026-08-06T11:20:22"))).isNull();
  }

  @Test
  void extractUpdatedAtShouldReturnNullWhenColumnValueMissing() {
    TableWhitelistOption option =
        new TableWhitelistOption(
            "issues", "Issues", "id", "updated_at", SourceCursorStrategy.PRIMARY_KEY_KEYSET, true);

    assertThat(service.extractUpdatedAt(option, Map.of())).isNull();
  }

  @Test
  void probeTableShouldReturnEmptyProbeWhenSourceHasNoRows() {
    TableWhitelistOption option =
        new TableWhitelistOption(
            "issues", "Issues", "id", "updated_at", SourceCursorStrategy.PRIMARY_KEY_KEYSET, true);

    assertThatThrownBy(() -> service.probeTable(dockerConfigWithoutContainer(), option))
        .isInstanceOf(BizException.class)
        .hasMessage("Docker mode requires a container name");
  }

  @Test
  void testConnectionShouldWrapNonBizFailuresAsConnectionFailure() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("127.0.0.1");
    config.setDbPort(1);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("secret");

    assertThatThrownBy(() -> service.testConnection(config))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("GitLab PostgreSQL connection failed");
  }

  private GitlabSyncConfig dockerConfigWithoutContainer() {
    return new GitlabSyncConfig();
  }
}
