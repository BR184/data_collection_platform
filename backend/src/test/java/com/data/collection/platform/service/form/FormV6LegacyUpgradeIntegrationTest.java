package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.migration.MigrationApplication;
import com.data.collection.platform.entity.form.FormActorContext;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M01 真实主线升级：只用批准主线迁移集建旧库，再切到目标迁移集执行低版本新增迁移。
 *
 * <p>旧库包含全部已发布迁移（含 {@code V20261019_01}），不含任何分支表单/发布读取脚本；升级使用
 * 实际应用同款 {@code validate/migrate}，并以 {@code outOfOrder} 显式执行低于旧库水位的新增迁移。
 * 不预装新脚本、不 repair、不改写已执行历史。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormV6LegacyUpgradeIntegrationTest {

  private static final String URL = "http://gitlab.n1-upgrade.local";
  private static final String MAINLINE_HEAD = "20261022.03";
  private static final String V6_HEAD = "20261105.03";
  private static final int MAINLINE_MIGRATIONS = 140;
  private static final int BRANCH_MIGRATIONS = 15;
  private static final FormActorContext ALICE =
      new FormActorContext("alice", "Alice", java.util.Set.of());

  /** 分支新增、批准主线没有的迁移；旧库构建时排除，升级时必须实际执行。 */
  private static final Set<String> BRANCH_ONLY = Set.of(
      "V20261022_01__unified_form_storage_foundation.sql",
      "V20261022_02__form_permissions.sql",
      "V20261022_04__retire_legacy_form_permissions.sql",
      "V20261027_01__form_v6_directory.sql",
      "V20261029_01__form_v6_publication_protocol.sql",
      "V20261029_02__form_publication_origin_binding.sql",
      "V20261030_01__form_consumption_capture_foundation.sql",
      "V20261102_02__form_review_fact_projection.sql",
      "V20261102_03__form_record_export.sql",
      "V20261102_06__form_project_candidates.sql",
      "V20261102_08__form_project_candidate_state.sql",
      "V20261104_01__form_issue_link_delivery.sql",
      "V20261105_01__independent_form_definitions.sql",
      "V20261105_02__form_issue_project_settings.sql",
      "V20261105_03__form_input_on_single_row_facts.sql");

  @Container
  private final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void shouldUpgradeRealMainlineDatabaseAndExecuteEveryLowVersionBranchMigration(
      @TempDir Path tempDir) throws Exception {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // 1) 只用批准主线迁移集（140 条，含 V20261019_01）建旧库。
    Path mainline = copyMainlineMigrations(tempDir);
    MigrateResult pre = Flyway.configure()
        .dataSource(dataSource)
        .locations("filesystem:" + mainline.toAbsolutePath())
        .load()
        .migrate();
    assertThat(pre.migrationsExecuted).isEqualTo(MAINLINE_MIGRATIONS);
    assertThat(headVersion(jdbc)).isEqualTo(MAINLINE_HEAD);
    assertThat(jdbc.queryForObject("select to_regclass('form_record_locations')", String.class))
        .isNull();
    assertThat(jdbc.queryForObject(
        "select to_regclass('customer_issue_delay_closure_requests')", String.class)).isNotNull();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where version = '20261019.01'"
            + " and success", Integer.class))
        .as("已发布的主线补丁必须真实存在于旧库")
        .isEqualTo(1);

    // 2) 真实非空旧数据：旧 Demo 记录与来源配置。表单目录此时尚不存在。
    long configId = jdbc.queryForObject(
        "insert into gitlab_sync_configs(name, db_name, db_username, db_password, web_base_url,"
            + " source_instance) values ('n1-upgrade', 'qaflex', 'qaflex', 'secret', ?,"
            + " 'n1-upgrade-src') returning id",
        Long.class, URL);
    FormV6TestSupport.insertLegacyPair(jdbc, 950001, 950002, URL, 41);
    List<Map<String, String>> legacyBefore = snapshotRows(jdbc, "collect_form_records");
    List<String> historyBefore = history(jdbc);

    // 3) 切到目标迁移集：通过实际无 Web 单次迁移入口显式开启 out-of-order。
    runMigrationEntry(dataSource, true);
    assertThat(history(jdbc).size()).isEqualTo(MAINLINE_MIGRATIONS + BRANCH_MIGRATIONS);
    assertThat(headVersion(jdbc)).isEqualTo(V6_HEAD);
    assertThat(jdbc.queryForObject("select to_regclass('read_publications')::text", String.class)).isNull();
    assertThat(history(jdbc).subList(0, MAINLINE_MIGRATIONS)).isEqualTo(historyBefore);
    assertThat(jdbc.queryForObject(
        "select to_regclass('form_record_locations')", String.class)).isNotNull();
    assertThat(jdbc.queryForList(
        "select version || ':' || description from flyway_schema_history"
            + " where version in ('20261022.03', '20261022.04') order by version", String.class))
        .containsExactly(
            "20261022.03:customer issue delay closure progress",
            "20261022.04:retire legacy form permissions");
    // 常驻/默认模式不携带 out-of-order：同一入口再次启动、校验通过后才允许继续。
    runMigrationEntry(dataSource, false);
    assertThat(headVersion(jdbc)).isEqualTo(V6_HEAD);
    assertThat(snapshotRows(jdbc, "collect_form_records")).isEqualTo(legacyBefore);

    // 4) 升级后的库立即可用：登记旧 Demo 并读回原值。
    FormV6TestSupport.WiredServices services = FormV6TestSupport.wire(jdbc);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "upgrade-mainline", configId, URL, 41, "DRAFT");
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(scopeId);
    assertThat(precheck.count()).isEqualTo(2);
    assertThat(services.tx().execute(status -> services.registrationService().registerLegacyDemo(
            scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash())).status())
        .isEqualTo("REGISTERED");
    assertThat(snapshotRows(jdbc, "collect_form_records")).isEqualTo(legacyBefore);
    assertThat(services.reader().listByResource(scopeId, "merge_request", "950001", 41))
        .singleElement()
        .satisfies(record -> assertThat(record.fields()).containsEntry("remark", "legacy"));
  }

  /** 走实际无 Web 单次迁移入口；out-of-order 只由该入口的显式参数开启。 */
  private static void runMigrationEntry(
      DriverManagerDataSource dataSource, boolean outOfOrder) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--spring.datasource.url=" + dataSource.getUrl(),
                "--spring.datasource.username=" + dataSource.getUsername(),
                "--spring.datasource.password=" + dataSource.getPassword(),
                "--spring.flyway.enabled=true",
                "--spring.flyway.schemas=public",
                "--spring.flyway.default-schema=public",
                "--spring.flyway.create-schemas=false",
                "--spring.flyway.locations=classpath:db/migration",
                "--spring.sql.init.mode=never",
                "--spring.main.banner-mode=off",
                "--spring.autoconfigure.exclude="
                    + "com.data.collection.platform.config.IntegrationTestFlywayConfiguration"));
    if (outOfOrder) {
      args.add("--spring.flyway.out-of-order=true");
    }
    MigrationApplication.main(args.toArray(String[]::new));
  }

  /** 从测试类路径复制主线迁移集，排除分支新增脚本并校验条数。 */
  private Path copyMainlineMigrations(Path tempDir) throws Exception {
    URL location = getClass().getClassLoader().getResource("db/migration");
    assertThat(location).isNotNull();
    Path source = Path.of(location.toURI());
    Path target = Files.createDirectories(tempDir.resolve("mainline-migrations"));
    int copied = 0;
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".sql") || BRANCH_ONLY.contains(name)) {
          continue;
        }
        Files.copy(file, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        copied++;
      }
    }
    assertThat(copied).as("旧库必须由批准主线迁移集构成").isEqualTo(MAINLINE_MIGRATIONS);
    return target;
  }

  private static List<String> history(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "select version || ':' || coalesce(checksum::text,'-') from flyway_schema_history"
            + " where version is not null order by installed_rank",
        String.class);
  }

  /** 行快照统一按字符串规范化，避免 {@code java.sql.Array} 的实例相等性造成假差异。 */
  private static List<Map<String, String>> snapshotRows(JdbcTemplate jdbc, String table) {
    return jdbc.query(
        "select * from " + table + " order by id",
        (rs, rowNum) -> {
          ResultSetMetaData meta = rs.getMetaData();
          Map<String, String> row = new LinkedHashMap<>();
          for (int index = 1; index <= meta.getColumnCount(); index++) {
            Object value = rs.getObject(index);
            if (value instanceof java.sql.Array array) {
              try {
                Object raw = array.getArray();
                if (raw instanceof Object[] items) {
                  List<String> parts = new java.util.ArrayList<>();
                  for (Object item : items) {
                    parts.add(item == null ? "" : String.valueOf(item));
                  }
                  value = String.join(",", parts);
                } else {
                  value = null;
                }
              } catch (java.sql.SQLException error) {
                throw new IllegalStateException("数组列快照失败", error);
              }
            }
            row.put(meta.getColumnLabel(index), value == null ? null : String.valueOf(value));
          }
          return row;
        });
  }

  private static String headVersion(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "select version from flyway_schema_history where version is not null"
            + " order by installed_rank desc limit 1",
        String.class);
  }
}
