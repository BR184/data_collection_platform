package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 冻结结构核验的聚焦回归：合法结构、CHECK 分组、外键动作、临时表跨连接清理及通用约束/索引/序列默认值。
 *
 * <p>使用正式生成器与真实 PostgreSQL；多连接入口按“每次借用独立物理连接且不归还”的方式强制校验实现
 * 必须在同一物理连接内创建、使用并清理冻结 CHECK 的临时表。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.form-publication.worker-enabled=false",
      "platform.gitlab-mirror.scheduler-enabled=false",
      "platform.gitlab-mirror.delete-reconciliation-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false",
      "platform.gitlab-mirror.code-review-metric-enrichment-enabled=false",
      "platform.review-data.search-index-backfill-enabled=false",
      "platform.auth.provider=local",
      "platform.auth.secure-config-required=false"
    })
class FormVersionStructureVerifierIntegrationTest {

  private static final long SCOPE_ID = 901L;
  private static final long VERSION_ID = 1L;

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @DynamicPropertySource
  static void registerIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.schemas", () -> "public");
    registry.add("spring.flyway.default-schema", () -> "public");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FormTemplateSpecCatalog catalog;
  @Autowired private FormVersionSchemaService schemaService;

  private FormVersionSchema schema;

  @BeforeEach
  void createGeneratedStructure() {
    dropTables();
    schema =
        schemaService.generate(
            SCOPE_ID,
            VERSION_ID,
            catalog.require(FormTemplateSpecCatalog.CODE_REVIEW),
            false);
    schema.statements().forEach(jdbcTemplate::execute);
  }

  @AfterEach
  void cleanUp() {
    dropTables();
  }

  @Test
  void shouldAcceptGeneratedStructureThroughMultiConnectionPoolAndCleanTempTables() throws Exception {
    try (PinnedDataSource dataSource =
        new PinnedDataSource(
            POSTGRES.getJdbcUrl() + "?currentSchema=public",
            POSTGRES.getUsername(),
            POSTGRES.getPassword())) {
      assertThat(dataSource.maximumPoolSize()).isGreaterThan(1);
      FormVersionStructureVerifier verifier = new FormVersionStructureVerifier(new JdbcTemplate(dataSource));

      verifier.verify(schema);

      assertThat(dataSource.probeConnections()).isNotEmpty();
      assertThat(dataSource.probeTableCount()).isZero();
    }
  }

  @Test
  void shouldRejectCheckLogicalGroupingChange() {
    String check =
        jdbcTemplate.queryForObject(
            """
            select conname from pg_constraint
             where conrelid = to_regclass(?) and contype = 'c'
               and pg_get_constraintdef(oid) like '%created_by%'
             order by conname limit 1
            """,
            String.class,
            schema.recordTable());
    assertThat(check).isNotBlank();
    jdbcTemplate.execute("alter table " + schema.recordTable() + " drop constraint " + check);
    jdbcTemplate.execute(
        "alter table "
            + schema.recordTable()
            + " add constraint "
            + check
            + " check ((created_by is null or request_key is not null)"
            + " and create_payload_hash is not null and template_version is not null)");

    assertThatThrownBy(() -> verifier().verify(schema))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("检查约束定义不一致");
  }

  @Test
  void shouldRejectForeignKeyDeleteActionChange() {
    String foreignKey =
        jdbcTemplate.queryForObject(
            """
            select conname from pg_constraint
             where conrelid = to_regclass(?) and contype = 'f'
             order by conname limit 1
            """,
            String.class,
            schema.recordTable());
    assertThat(foreignKey).isNotBlank();
    jdbcTemplate.execute(
        "alter table " + schema.recordTable() + " drop constraint " + foreignKey);
    jdbcTemplate.execute(
        "alter table "
            + schema.recordTable()
            + " add constraint "
            + foreignKey
            + " foreign key (instance_id) references "
            + schema.instanceTable()
            + " (id) on delete cascade");

    assertThatThrownBy(() -> verifier().verify(schema))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("外键执行属性不一致");
  }

  @Test
  void shouldRejectMissingUniqueConstraint() {
    String unique =
        jdbcTemplate.queryForObject(
            """
            select conname from pg_constraint
             where conrelid = to_regclass(?) and contype = 'u'
             order by conname limit 1
            """,
            String.class,
            schema.recordTable());
    assertThat(unique).isNotBlank();
    jdbcTemplate.execute("alter table " + schema.recordTable() + " drop constraint " + unique);

    assertThatThrownBy(() -> verifier().verify(schema))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("约束集合不一致");
  }

  @Test
  void shouldRejectMissingRecordIndex() {
    String index = schema.indexes().get(0).indexName();
    assertThat(index).isNotBlank();
    jdbcTemplate.execute("drop index " + index);

    assertThatThrownBy(() -> verifier().verify(schema))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少索引");
  }

  @Test
  void shouldRejectRecordIdDefaultSequenceDrift() {
    jdbcTemplate.execute("create sequence form_record_id_probe_seq");
    jdbcTemplate.execute(
        "alter table "
            + schema.recordTable()
            + " alter column id set default nextval('form_record_id_probe_seq')");

    assertThatThrownBy(() -> verifier().verify(schema))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("记录ID默认序列不一致");
  }

  private FormVersionStructureVerifier verifier() {
    return new FormVersionStructureVerifier(jdbcTemplate);
  }

  private void dropTables() {
    jdbcTemplate.execute(
        "drop table if exists " + scopeTable(SCOPE_ID, VERSION_ID, "records") + " cascade");
    jdbcTemplate.execute(
        "drop table if exists " + scopeTable(SCOPE_ID, VERSION_ID, "instances") + " cascade");
    jdbcTemplate.execute("drop sequence if exists form_record_id_probe_seq");
  }

  private static String scopeTable(long scopeId, long versionId, String suffix) {
    return "form_s" + scopeId + "_v" + versionId + "_" + suffix;
  }

  /**
   * 每次借出独立物理连接，且一旦在该连接上创建临时表就不再归还，迫使后续调用只能换连接。
   *
   * <p>这样未把临时表使用固定在单一会话内的实现会立即失败，而正确实现仍能在同一连接内完成
   * 创建、读取和删除；关闭时逐个核对并释放探针会话。
   */
  private static final class PinnedDataSource implements DataSource, AutoCloseable {

    private final HikariDataSource pool;
    private final Set<Connection> probes = Collections.newSetFromMap(new IdentityHashMap<>());

    private PinnedDataSource(String jdbcUrl, String username, String password) {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(jdbcUrl);
      config.setUsername(username);
      config.setPassword(password);
      config.setMaximumPoolSize(64);
      config.setMinimumIdle(0);
      config.setConnectionTimeout(10000);
      this.pool = new HikariDataSource(config);
    }

    private int maximumPoolSize() {
      return pool.getMaximumPoolSize();
    }

    private Set<Connection> probeConnections() {
      return probes;
    }

    private int probeTableCount() throws SQLException {
      int count = 0;
      for (Connection connection : probes) {
        try (PreparedStatement statement =
                connection.prepareStatement(
                    "select count(*) from pg_class where relname like 'form\\_ck\\_probe\\_%'"
                        + " and relnamespace = pg_my_temp_schema()");
            ResultSet result = statement.executeQuery()) {
          if (result.next()) {
            count += result.getInt(1);
          }
        }
      }
      return count;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = pool.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                  if (!probes.contains(delegate)) {
                    delegate.close();
                  }
                  return null;
                }
                Object result = invoke(delegate, method, args);
                if ("createStatement".equals(method.getName())) {
                  return statementProxy(delegate, (Statement) result);
                }
                return result;
              });
    }

    private Statement statementProxy(Connection delegate, Statement statement) {
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {Statement.class},
              (proxy, method, args) -> {
                if (args != null
                    && args.length > 0
                    && args[0] instanceof String sql
                    && sql.startsWith("create temp table")) {
                  probes.add(delegate);
                }
                return invoke(statement, method, args);
              });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
        throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public void close() {
      for (Connection connection : probes) {
        try {
          connection.close();
        } catch (SQLException ignored) {
          // 连接池关闭时统一释放
        }
      }
      pool.close();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return pool.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      pool.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      pool.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return pool.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return pool.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return pool.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return pool.isWrapperFor(iface);
    }
  }
}
