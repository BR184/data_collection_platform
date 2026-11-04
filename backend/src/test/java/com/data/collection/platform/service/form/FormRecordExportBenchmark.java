package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * R08 固定负载基准：按 1,000 与 50,000 行测量按版导出的内容、SQL 数、峰值内存与耗时。
 *
 * <p>类名不匹配默认 surefire 的 {@code *Test} 模式，不进入默认套件；用
 * {@code mvn test -Dtest=FormRecordExportBenchmark} 单独运行。场景固定为单版本 MATERIATED
 * 记录、同一创建人，测量值只反映本机测试容器，不外推到内网容量。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormRecordExportBenchmark {

  private static final String URL = "http://gitlab.bench.local";
  private static final long PROJECT_ID = 11L;
  private static final long INSTANCE_BASE = 2_000_000L;
  private static final long RECORD_BASE = 3_000_000L;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FormTemplateSpecCatalog CATALOG = new FormTemplateSpecCatalog(MAPPER);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void measuresThousandAndFiftyThousandRowExports() throws Exception {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(POSTGRES);
    FormV6TestSupport.WiredServices services = FormV6TestSupport.wire(jdbc);
    jdbc.update(
        "insert into platform_ldap_users(user_id, real_name) values ('u-bench', 'Bench')"
            + " on conflict (user_id) do update set real_name = excluded.real_name");
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId =
        FormV6TestSupport.insertScope(
            jdbc,
            "code_review",
            "bench-export",
            configId,
            URL,
            List.of(PROJECT_ID),
            PROJECT_ID,
            "DRAFT");
    FormV6TestSupport.FixtureTables tables =
        FormV6TestSupport.createRecordVersion(
            jdbc,
            scopeId,
            "code_review",
            "BENCH",
            CATALOG.rawJson(FormTemplateSpecCatalog.CODE_REVIEW),
            "PUBLISHED",
            true);

    CountingDataSource counting = new CountingDataSource(jdbc.getDataSource());
    JdbcTemplate countingJdbc = new JdbcTemplate(counting);
    FormRecordDirectoryReader countingReader =
        new FormRecordDirectoryReader(countingJdbc, services.contextService());
    FormRecordExportService exportService =
        new FormRecordExportService(
            countingJdbc, countingReader, services.contextService(), services.objectMapper());

    insertRange(jdbc, tables, configId, scopeId, 1, 1_000);
    measure(exportService, counting, tables.versionId(), "1000", 1_000);

    insertRange(jdbc, tables, configId, scopeId, 1_001, 50_000);
    measure(exportService, counting, tables.versionId(), "50000", 50_000);
  }

  private void measure(
      FormRecordExportService exportService,
      CountingDataSource counting,
      long versionId,
      String label,
      int expectedRows)
      throws Exception {
    System.gc();
    Thread.sleep(100L);
    counting.reset();
    MemorySampler sampler = new MemorySampler();
    sampler.start();
    long started = System.nanoTime();
    FormRecordExportService.ExportResult result =
        exportService.export(versionId, List.of(), "bench");
    long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
    sampler.stop();
    if (result.rowCount() != expectedRows) {
      throw new AssertionError("行数不符：" + result.rowCount() + " != " + expectedRows);
    }
    long expectedSql = 4L + (expectedRows + 499L) / 500L;
    if (counting.count() > expectedSql) {
      throw new AssertionError(
          "SQL 数超出固定开销加每批上限：" + counting.count() + " > " + expectedSql);
    }
    if (expectedRows == 1_000) {
      verifyContent(result);
    }
    System.out.println(
        "R08_BENCH"
            + " rows=" + label
            + " sql=" + counting.count()
            + " peakMemoryMb=" + String.format("%.1f", sampler.peakBytes() / 1024.0 / 1024.0)
            + " elapsedMs=" + elapsedMs
            + " bytes=" + result.byteSize()
            + " sha256=" + result.sha256());
  }

  private void verifyContent(FormRecordExportService.ExportResult result) throws Exception {
    try (XSSFWorkbook workbook =
        new XSSFWorkbook(new ByteArrayInputStream(result.content()))) {
      var sheet = workbook.getSheetAt(0);
      if (!"记录ID".equals(sheet.getRow(0).getCell(0).getStringCellValue())) {
        throw new AssertionError("表头与冻结规格不一致");
      }
      if (!Long.toString(RECORD_BASE + 1).equals(sheet.getRow(1).getCell(0).getStringCellValue())
          || !"Bench".equals(sheet.getRow(1).getCell(5).getStringCellValue())) {
        throw new AssertionError("首行内容与记录证据不一致");
      }
      if (sheet.getLastRowNum() != 1_000) {
        throw new AssertionError("工作表行数与导出记录数不一致：" + sheet.getLastRowNum());
      }
    }
  }

  private void insertRange(
      JdbcTemplate jdbc,
      FormV6TestSupport.FixtureTables tables,
      long configId,
      long scopeId,
      int from,
      int to) {
    jdbc.update(
        "insert into " + tables.instanceTable()
            + "(id, scope_id, source_config_id, source_instance, gitlab_base_url, project_id,"
            + " resource_type, resource_id, template_key, created_at, updated_at)"
            + " select ? + g, ?, ?, 'default', ?, ?, 'merge_request', g, 'code_review',"
            + " current_timestamp, current_timestamp from generate_series(?, ?) g",
        INSTANCE_BASE, scopeId, configId, URL, PROJECT_ID, from, to);
    jdbc.update(
        "insert into " + tables.recordTable()
            + "(id, instance_id, origin_version_id, record_version, deleted, created_by,"
            + " template_version, request_key, create_payload_hash, created_at, updated_at,"
            + " reviewer_user_id, reviewer_name, review_duration_minutes, specification_count,"
            + " logic_count, performance_count, design_count, other_count)"
            + " select ? + g, ? + g, ?, 1, false, 'u-bench', 'v-test', 'req-' || g,"
            + " repeat('b', 64), current_timestamp, current_timestamp, 'u-bench', 'Bench', 30, 1,"
            + " 0, 0, 0, 0 from generate_series(?, ?) g",
        RECORD_BASE, INSTANCE_BASE, tables.versionId(), from, to);
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        select ? + g, ?, ?, ?, 'MATERIALIZED', ? + g, ?, 'default', ?, ?, 'merge_request', g::text, null,
               'u-bench',
               (select form_spec_hash(field_spec)::char(64) from form_template_versions where id = ?),
               null, current_timestamp
          from generate_series(?, ?) g
        """,
        RECORD_BASE, scopeId, tables.versionId(), tables.versionId(), INSTANCE_BASE, configId,
        URL, PROJECT_ID, tables.versionId(), from, to);
  }

  private static final class CountingDataSource implements DataSource {
    private final DataSource delegate;
    private final AtomicLong statements = new AtomicLong();

    private CountingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    long count() {
      return statements.get();
    }

    void reset() {
      statements.set(0L);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
      return (Connection)
          Proxy.newProxyInstance(
              FormRecordExportBenchmark.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().startsWith("prepare")) {
                  statements.incrementAndGet();
                }
                try {
                  return method.invoke(connection, args);
                } catch (InvocationTargetException error) {
                  throw error.getCause();
                }
              });
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }

  private static final class MemorySampler {
    private final Thread thread;
    private volatile boolean running = true;
    private volatile long peak;

    private MemorySampler() {
      thread =
          new Thread(
              () -> {
                Runtime runtime = Runtime.getRuntime();
                while (running) {
                  peak = Math.max(peak, runtime.totalMemory() - runtime.freeMemory());
                  try {
                    Thread.sleep(10L);
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
              },
              "r08-bench-memory");
      thread.setDaemon(true);
    }

    void start() {
      thread.start();
    }

    void stop() throws InterruptedException {
      running = false;
      thread.join(1_000L);
    }

    long peakBytes() {
      return peak;
    }
  }
}
