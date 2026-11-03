package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.config.PlatformJacksonConfiguration;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.service.sync.SyncThreadBudgetResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ResourceLock("default-time-zone")
class GitlabMirrorTimestampTransportIntegrationTest {
  private static final String GITLAB_PSQL_SHIM =
      """
      #!/bin/sh
      exec psql -U gitlab "$@"
      """;

  @Container
  private static final PostgreSQLContainer<?> SOURCE =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("gitlabhq_production")
          .withUsername("gitlab")
          .withPassword("secret");

  @Container
  private static final PostgreSQLContainer<?> TARGET =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private static TimeZone originalTimeZone;
  private HikariDataSource targetDataSource;
  private GitlabDirectJdbcExecutor directExecutor;

  @BeforeAll
  static void setUp() throws Exception {
    originalTimeZone = TimeZone.getDefault();
    SOURCE.execInContainer(
        "sh",
        "-c",
        "printf '%s' '"
            + GITLAB_PSQL_SHIM.replace("'", "'\\''")
            + "' > /usr/local/bin/gitlab-psql && "
            + "chmod +x /usr/local/bin/gitlab-psql");
    try (Connection connection = sourceConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create table issues (
            id bigint primary key,
            updated_at timestamp not null,
            wall_time timestamp,
            instant_time timestamptz,
            closed_at timestamptz
          )
          """);
      statement.execute(
          """
          create table events (
            id bigint primary key,
            updated_at timestamptz,
            created_at timestamptz
          )
          """);
      statement.execute(
          """
          create table resource_label_events (
            id bigint primary key,
            issue_id bigint not null,
            action smallint not null,
            created_at timestamptz
          )
          """);
      statement.execute(
          """
          insert into issues(id, updated_at, wall_time, instant_time, closed_at) values
            (101,
             timestamp '2026-06-25 10:00:00.123456',
             timestamp '2026-06-25 10:53:19.403496',
             timestamptz '2026-06-25 10:53:19.403496+00',
             timestamptz '2026-06-25 11:53:19.403496+00'),
            (102,
             timestamp '2026-06-25 23:59:59.999999',
             timestamp '2026-06-26 06:59:59.999999',
             timestamptz '2026-06-25 23:59:59.999999-07',
             timestamptz '2026-06-26 06:59:59.999999+00'),
            (103, timestamp '2026-06-26 00:00:00', null, null, null),
            (104,
             timestamp '2026-06-25 10:00:00.123456',
             timestamp '2026-06-26 02:53:19.403496',
             timestamptz '2026-06-25 18:53:19.403496+08',
             timestamptz '2026-06-25 19:53:19.403496+08'),
            (105,
             timestamp '2026-06-25 10:00:00.123456',
             timestamp '2026-06-26 02:53:19.403496',
             timestamptz '2026-06-25T10:53:19.403496Z',
             timestamptz '2026-06-25T11:53:19.403496Z')
          """);
      statement.execute(
          """
          insert into events(id, updated_at, created_at) values
            (201,
             timestamptz '2026-06-25 10:53:19.403496+00',
             timestamptz '2026-06-25 10:53:19.403496+00'),
            (202,
             timestamptz '2026-06-25 23:59:59.999999-07',
             timestamptz '2026-06-26 06:59:59.999999+00'),
            (203, null, null)
          """);
      statement.execute(
          """
          insert into resource_label_events(id, issue_id, action, created_at) values
            (301, 101, 1, timestamptz '2026-06-25 10:53:19.403496+00'),
            (302, 102, 1, timestamptz '2026-06-25 23:59:59.999999-07'),
            (303, 103, 1, null)
          """);
    }
    try (Connection connection =
            DriverManager.getConnection(
                TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create table ods_gitlab_issues (
            mirror_id bigserial primary key,
            id bigint not null,
            updated_at timestamp without time zone,
            wall_time timestamp without time zone,
            instant_time timestamp with time zone,
            closed_at timestamp with time zone,
            mirror_task_id bigint,
            source_updated_at timestamp without time zone,
            mirror_synced_at timestamp not null default current_timestamp,
            mirror_deleted boolean not null default false,
            mirror_created_at timestamp not null default current_timestamp,
            mirror_updated_at timestamp not null default current_timestamp
          )
          """);
      statement.execute("create unique index uq_ods_gitlab_issues_id on ods_gitlab_issues(id)");
      statement.execute(
          """
          create table ods_gitlab_events (
            mirror_id bigserial primary key,
            id bigint not null,
            updated_at timestamp with time zone,
            created_at timestamp with time zone,
            mirror_task_id bigint,
            source_updated_at timestamp without time zone,
            mirror_synced_at timestamp not null default current_timestamp,
            mirror_deleted boolean not null default false,
            mirror_created_at timestamp not null default current_timestamp,
            mirror_updated_at timestamp not null default current_timestamp
          )
          """);
      statement.execute("create unique index uq_ods_gitlab_events_id on ods_gitlab_events(id)");
      statement.execute(
          """
          create table ods_gitlab_resource_label_events (
            mirror_id bigserial primary key,
            id bigint not null,
            issue_id bigint not null,
            action smallint not null,
            created_at timestamp with time zone,
            mirror_task_id bigint,
            source_updated_at timestamp without time zone,
            mirror_synced_at timestamp not null default current_timestamp,
            mirror_deleted boolean not null default false,
            mirror_created_at timestamp not null default current_timestamp,
            mirror_updated_at timestamp not null default current_timestamp
          )
          """);
      statement.execute(
          "create unique index uq_ods_gitlab_resource_label_events_id "
              + "on ods_gitlab_resource_label_events(id)");
    }
  }

  @AfterAll
  static void restoreTimeZone() {
    TimeZone.setDefault(originalTimeZone);
  }

  @AfterEach
  void closeResources() {
    if (directExecutor != null) {
      directExecutor.destroy();
      directExecutor = null;
    }
    if (targetDataSource != null) {
      targetDataSource.close();
      targetDataSource = null;
    }
  }

  @Test
  void test_direct_transport_preserves_source_instants_and_wall_clocks() throws Exception {
    verifyTransport(SourceMode.DIRECT, new Scenario("UTC", "Asia/Shanghai", "Asia/Shanghai"));
    verifyTransport(
        SourceMode.DIRECT,
        new Scenario("Asia/Shanghai", "UTC", "UTC"));
    verifyTransport(
        SourceMode.DIRECT,
        new Scenario("America/Los_Angeles", "America/New_York", "America/Los_Angeles"));
  }

  @Test
  void test_docker_transport_preserves_source_instants_and_wall_clocks() throws Exception {
    verifyTransport(SourceMode.DOCKER, new Scenario("UTC", "Asia/Shanghai", "Asia/Shanghai"));
    verifyTransport(
        SourceMode.DOCKER,
        new Scenario("Asia/Shanghai", "UTC", "UTC"));
    verifyTransport(
        SourceMode.DOCKER,
        new Scenario("America/Los_Angeles", "America/New_York", "America/Los_Angeles"));
  }

  @Test
  void test_force_reapply_corrects_legacy_shift_without_duplicate_rows() throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    setSourceTimeZone("UTC");
    targetDataSource = targetDataSource("Asia/Shanghai");
    JdbcTemplate target = new JdbcTemplate(targetDataSource);
    target.update("truncate ods_gitlab_issues, ods_gitlab_events, ods_gitlab_resource_label_events");
    Harness harness = harness(SourceMode.DIRECT);
    TableWhitelistOption option =
        new TableWhitelistOption(
            "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true);
    List<Map<String, Object>> rows = harness.external().fullTableScan(harness.config(), option);
    harness.storage().applyBatch(issuesSchema(), rows, 1L, true);
    BigDecimal correctEpoch =
        target.queryForObject(
            "select extract(epoch from instant_time) from ods_gitlab_issues where id = 101",
            BigDecimal.class);

    target.update(
        "update ods_gitlab_issues "
            + "set instant_time = instant_time - interval '8 hours', "
            + "closed_at = closed_at - interval '8 hours' where id = 101");
    assertThat(
            target.queryForObject(
                "select extract(epoch from instant_time) from ods_gitlab_issues where id = 101",
                BigDecimal.class))
        .isNotEqualByComparingTo(correctEpoch);

    MirrorMutationResult repaired = harness.storage().applyBatch(issuesSchema(), rows, 2L, true);

    assertThat(repaired.changes()).isNotEmpty();
    assertThat(
            target.queryForObject(
                "select extract(epoch from instant_time) from ods_gitlab_issues where id = 101",
                BigDecimal.class))
        .isEqualByComparingTo(correctEpoch);
    MirrorMutationResult repeated = harness.storage().applyBatch(issuesSchema(), rows, 3L, true);
    assertThat(repeated.changes()).isEmpty();
    assertThat(target.queryForObject("select count(*) from ods_gitlab_issues", Integer.class))
        .isEqualTo(5);
  }

  private void verifyTransport(SourceMode mode, Scenario scenario) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(scenario.jvmZone()));
    setSourceTimeZone(scenario.sourceZone());
    targetDataSource = targetDataSource(scenario.targetZone());
    JdbcTemplate target = new JdbcTemplate(targetDataSource);
    target.update("truncate ods_gitlab_issues, ods_gitlab_events, ods_gitlab_resource_label_events");
    Harness harness = harness(mode);

    harness.storage().applyBatch(issuesSchema(), harness.external().fullTableScan(
        harness.config(),
        new TableWhitelistOption(
            "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true)), 1L, true);
    harness.storage().applyBatch(eventsSchema(), harness.external().fullTableScan(
        harness.config(),
        new TableWhitelistOption(
            "events", "events", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true)), 1L, true);
    harness.storage().applyBatch(labelEventsSchema(), harness.external().fullTableScan(
        harness.config(),
        new TableWhitelistOption(
            "resource_label_events",
            "resource_label_events",
            "id",
            null,
            SourceCursorStrategy.NONE,
            true)), 1L, true);

    assertIssues(target, scenario);
    assertEvents(target, scenario);
    assertLabelEvents(target, scenario);
  }

  private void assertIssues(JdbcTemplate target, Scenario scenario) {
    Map<Long, SourceIssue> source = sourceIssues();
    List<Map<String, Object>> actual =
        target.queryForList(
            """
            select id,
                   extract(epoch from instant_time) as instant_epoch,
                   to_char(wall_time, 'YYYY-MM-DD HH24:MI:SS.US') as wall_text,
                   to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS.US') as updated_text,
                   to_char(source_updated_at, 'YYYY-MM-DD HH24:MI:SS.US') as source_updated_text
              from ods_gitlab_issues
             order by id
            """);
    assertThat(actual).hasSize(5);
    for (Map<String, Object> row : actual) {
      SourceIssue expected = source.get(((Number) row.get("id")).longValue());
      assertEpoch(row.get("instant_epoch"), expected.instantEpoch(), "issues.instant_time " + scenario);
      assertThat(row.get("wall_text"))
          .as("issues.wall_time " + scenario)
          .isEqualTo(expected.wallText());
      assertThat(row.get("updated_text"))
          .as("issues.updated_at " + scenario)
          .isEqualTo(expected.updatedText());
      assertThat(row.get("source_updated_text"))
          .as("issues.source_updated_at " + scenario)
          .isEqualTo(expected.updatedText());
    }
  }

  private void assertEvents(JdbcTemplate target, Scenario scenario) {
    Map<Long, SourceEvent> source = sourceEvents();
    List<Map<String, Object>> actual =
        target.queryForList(
            """
            select id,
                   extract(epoch from updated_at) as updated_epoch,
                   extract(epoch from created_at) as created_epoch,
                   to_char(source_updated_at, 'YYYY-MM-DD HH24:MI:SS.US') as source_updated_text
              from ods_gitlab_events
             order by id
            """);
    assertThat(actual).hasSize(3);
    for (Map<String, Object> row : actual) {
      SourceEvent expected = source.get(((Number) row.get("id")).longValue());
      assertEpoch(row.get("updated_epoch"), expected.updatedEpoch(), "events.updated_at " + scenario);
      assertEpoch(row.get("created_epoch"), expected.createdEpoch(), "events.created_at " + scenario);
      assertThat(row.get("source_updated_text"))
          .as("events.source_updated_at " + scenario)
          .isEqualTo(expected.updatedUtcWall());
    }
  }

  private void assertLabelEvents(JdbcTemplate target, Scenario scenario) {
    Map<Long, BigDecimal> source = sourceLabelEventEpochs();
    List<Map<String, Object>> actual =
        target.queryForList(
            "select id, extract(epoch from created_at) as created_epoch "
                + "from ods_gitlab_resource_label_events order by id");
    assertThat(actual).hasSize(3);
    for (Map<String, Object> row : actual) {
      assertEpoch(
          row.get("created_epoch"),
          source.get(((Number) row.get("id")).longValue()),
          "resource_label_events.created_at " + scenario);
    }
  }

  private Harness harness(SourceMode mode) {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryTimeoutSeconds(30);
    ObjectMapper objectMapper =
        new Jackson2ObjectMapperBuilder()
            .timeZone(TimeZone.getTimeZone(PlatformJacksonConfiguration.API_TIME_ZONE))
            .serializerByType(
                LocalDateTime.class, PlatformJacksonConfiguration.localDateTimeSerializer())
            .deserializerByType(
                LocalDateTime.class, PlatformJacksonConfiguration.localDateTimeDeserializer())
            .build();
    JsonUtils jsonUtils = new JsonUtils(objectMapper);
    GitlabSourceConnectionSettings connectionSettings =
        new GitlabSourceConnectionSettings(properties);
    GitlabSourceQueryRetryPolicy retryPolicy = new GitlabSourceQueryRetryPolicy(properties);
    directExecutor =
        new GitlabDirectJdbcExecutor(
            connectionSettings,
            retryPolicy,
            new GitlabJdbcValueNormalizer(),
            new SyncThreadBudgetResolver(properties));
    GitlabDockerPsqlExecutor dockerExecutor =
        new GitlabDockerPsqlExecutor(properties, connectionSettings, retryPolicy, objectMapper);
    GitlabSourceQueryDispatcher dispatcher =
        new GitlabSourceQueryDispatcher(directExecutor, dockerExecutor);
    GitlabSourceMetadataSupport metadataSupport = new GitlabSourceMetadataSupport();
    GitlabExternalDbService external =
        new GitlabExternalDbService(
            new GitlabSourceScanSqlBuilder(jsonUtils),
            new GitlabPrimaryKeyExistenceQueryBuilder(),
            new GitlabAuthoritativeScopeQueryBuilder(),
            directExecutor,
            dispatcher,
            new GitlabSourceSchemaDiscoveryService(dispatcher::query, metadataSupport),
            metadataSupport);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    config.setSourceMode(mode);
    config.setDbHost(SOURCE.getHost());
    config.setDbPort(SOURCE.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
    config.setDbName(SOURCE.getDatabaseName());
    config.setDbUsername(SOURCE.getUsername());
    config.setDbPassword(SOURCE.getPassword());
    config.setDockerContainerName(SOURCE.getContainerName());
    GitlabMirrorTableStorageService storage =
        new GitlabMirrorTableStorageService(
            new JdbcTemplate(targetDataSource), jsonUtils);
    return new Harness(config, external, storage);
  }

  private HikariDataSource targetDataSource(String zone) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(TARGET.getJdbcUrl());
    config.setUsername(TARGET.getUsername());
    config.setPassword(TARGET.getPassword());
    config.setMaximumPoolSize(2);
    config.setConnectionInitSql("set time zone '" + zone + "'");
    return new HikariDataSource(config);
  }

  private static Connection sourceConnection() throws Exception {
    return DriverManager.getConnection(
        SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
  }

  private static void setSourceTimeZone(String zone) throws Exception {
    try (Connection connection = sourceConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("alter database gitlabhq_production set timezone to '" + zone + "'");
    }
  }

  private static Map<Long, SourceIssue> sourceIssues() {
    try (Connection connection = sourceConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                """
                select id,
                       extract(epoch from instant_time) as instant_epoch,
                       to_char(wall_time, 'YYYY-MM-DD HH24:MI:SS.US') as wall_text,
                       to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS.US') as updated_text
                  from issues
                 order by id
                """)) {
      Map<Long, SourceIssue> rows = new LinkedHashMap<>();
      while (resultSet.next()) {
        rows.put(
            resultSet.getLong("id"),
            new SourceIssue(
                resultSet.getBigDecimal("instant_epoch"),
                resultSet.getString("wall_text"),
                resultSet.getString("updated_text")));
      }
      return rows;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static Map<Long, SourceEvent> sourceEvents() {
    try (Connection connection = sourceConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                """
                select id,
                       extract(epoch from updated_at) as updated_epoch,
                       extract(epoch from created_at) as created_epoch,
                       to_char(updated_at at time zone 'UTC',
                               'YYYY-MM-DD HH24:MI:SS.US') as updated_utc_wall
                  from events
                 order by id
                """)) {
      Map<Long, SourceEvent> rows = new LinkedHashMap<>();
      while (resultSet.next()) {
        rows.put(
            resultSet.getLong("id"),
            new SourceEvent(
                resultSet.getBigDecimal("updated_epoch"),
                resultSet.getBigDecimal("created_epoch"),
                resultSet.getString("updated_utc_wall")));
      }
      return rows;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static Map<Long, BigDecimal> sourceLabelEventEpochs() {
    try (Connection connection = sourceConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select id, extract(epoch from created_at) as created_epoch "
                    + "from resource_label_events order by id")) {
      Map<Long, BigDecimal> rows = new LinkedHashMap<>();
      while (resultSet.next()) {
        rows.put(resultSet.getLong("id"), resultSet.getBigDecimal("created_epoch"));
      }
      return rows;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static void assertEpoch(Object actual, BigDecimal expected, String description) {
    BigDecimal actualEpoch = epoch(actual);
    if (expected == null) {
      assertThat(actualEpoch).as(description).isNull();
      return;
    }
    assertThat(actualEpoch).as(description).isEqualByComparingTo(expected);
  }

  private static BigDecimal epoch(Object value) {
    if (value == null) {
      return null;
    }
    return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
  }

  private static SourceTableSchema issuesSchema() {
    return new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 2),
            new SourceTableColumn("wall_time", "timestamp without time zone", true, 3),
            new SourceTableColumn("instant_time", "timestamp with time zone", true, 4),
            new SourceTableColumn("closed_at", "timestamp with time zone", true, 5)));
  }

  private static SourceTableSchema eventsSchema() {
    return new SourceTableSchema(
        "ods_gitlab_events",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp with time zone", true, 2),
            new SourceTableColumn("created_at", "timestamp with time zone", true, 3)));
  }

  private static SourceTableSchema labelEventsSchema() {
    return new SourceTableSchema(
        "ods_gitlab_resource_label_events",
        List.of("id"),
        null,
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("issue_id", "bigint", false, 2),
            new SourceTableColumn("action", "smallint", false, 3),
            new SourceTableColumn("created_at", "timestamp with time zone", true, 4)));
  }

  private record Scenario(String sourceZone, String targetZone, String jvmZone) {}

  private record Harness(
      GitlabSyncConfig config,
      GitlabExternalDbService external,
      GitlabMirrorTableStorageService storage) {}

  private record SourceIssue(
      BigDecimal instantEpoch, String wallText, String updatedText) {}

  private record SourceEvent(
      BigDecimal updatedEpoch, BigDecimal createdEpoch, String updatedUtcWall) {}
}
