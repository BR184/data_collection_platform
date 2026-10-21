package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 模拟老接口按 Data_Source 头选库更新，以真实 MySQL → PostgreSQL → 兼容非法列表验证刷新。
 * 不启动爬虫、不调用生产接口；同号跨源记录和其它 MR 用于锁定替换边界。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeReviewMatchModeLegacyRefreshIntegrationTest {
  private static final String TEST_PASSWORD = "refresh-test-only";
  private static final long TARGET_IID = 22273L;
  private static final String ILLEGAL_SCAN_RESULT = "静态扫描问题未关闭";

  @Container
  private static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.0")
      .withEnv("MYSQL_ROOT_PASSWORD", TEST_PASSWORD)
      .withEnv("MYSQL_ROOT_HOST", "%")
      .withExposedPorts(3306)
      .waitingFor(Wait.forLogMessage(".*port: 3306.*\\n", 1));

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("qaflex")
      .withUsername("qaflex")
      .withPassword(TEST_PASSWORD);

  private JdbcTemplate platformJdbc;
  private JdbcTemplate ccJdbc;
  private JdbcTemplate dgmJdbc;
  private HttpServer legacyServer;
  private CodeReviewMatchModeSyncService syncService;
  private CodeReviewMatchModeLegacyRefreshService refreshService;
  private CodeReviewMatchModeRecordLoader recordLoader;
  private CodeReviewMatchModeSwitchService switchService;

  @BeforeAll
  void createDatabases() {
    JdbcTemplate mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl(""), "root", TEST_PASSWORD));
    mysqlAdmin.execute("create database legacy_cc character set utf8mb4");
    mysqlAdmin.execute("create database legacy_dgm character set utf8mb4");
    ccJdbc = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl("legacy_cc"), "root", TEST_PASSWORD));
    dgmJdbc = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl("legacy_dgm"), "root", TEST_PASSWORD));
    createLegacyTable(ccJdbc);
    createLegacyTable(dgmJdbc);
    DriverManagerDataSource platformDataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(platformDataSource).locations("classpath:db/migration").load().migrate();
    platformJdbc = new JdbcTemplate(platformDataSource);
    recordLoader = new CodeReviewMatchModeRecordLoader(platformJdbc);
  }

  @BeforeEach
  void setUp() throws Exception {
    ccJdbc.update("delete from spider_crowncad_data");
    dgmJdbc.update("delete from spider_crowncad_data");
    platformJdbc.update("delete from code_review_match_mode_records");
    insertLegacyRow(ccJdbc, 1, "CrownCAD", TARGET_IID, "CC走查人");
    insertLegacyRow(ccJdbc, 2, "CrownCAD", 22274L, "CC其它记录");
    insertLegacyRow(dgmJdbc, 1, "DGM", TARGET_IID, "DGM走查人甲");
    insertLegacyRow(dgmJdbc, 2, "DGM", TARGET_IID, "DGM走查人乙");
    insertLegacyRow(dgmJdbc, 3, "DGM", 22274L, "DGM其它记录");

    // 只复现已核实的老平台选源与写库契约；响应发出前完成目标库更新。
    legacyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    legacyServer.createContext("/mergeRequest/updataByIssuableReferenceAndStatus", exchange -> {
      JdbcTemplate sourceJdbc = "DGM".equals(exchange.getRequestHeaders().getFirst("Data_Source"))
          ? dgmJdbc : ccJdbc;
      String query = exchange.getRequestURI().getRawQuery();
      String iid = java.util.Arrays.stream(query.split("&"))
          .filter(parameter -> parameter.startsWith("issuableReference="))
          .map(parameter -> parameter.substring("issuableReference=".length()))
          .findFirst().orElseThrow();
      sourceJdbc.update(
          "update spider_crowncad_data set bug_count_result = '通过' where issuable_reference = ?", iid);
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    legacyServer.start();
    String baseUrl = "http://127.0.0.1:" + legacyServer.getAddress().getPort();
    CodeReviewMatchModeConfig config = new CodeReviewMatchModeConfig(
        mysqlUrl("legacy_cc"), mysqlUrl("legacy_dgm"), "root", TEST_PASSWORD, "spider_crowncad_data",
        baseUrl, baseUrl, List.of("spider_crowncad_data"), 100,
        "", "", List.of(), "", "", "compatibility", "compatibility", false);
    CodeReviewMatchModeConfigService configService = mock(CodeReviewMatchModeConfigService.class);
    when(configService.loadConfig()).thenReturn(config);
    when(configService.legacyApiBaseUrlForSource(config, "cc")).thenReturn(baseUrl);
    when(configService.legacyApiBaseUrlForSource(config, "dgm")).thenReturn(baseUrl);
    switchService = mock(CodeReviewMatchModeSwitchService.class);
    when(switchService.isEnabled()).thenReturn(true);
    syncService = new CodeReviewMatchModeSyncService(
        configService, switchService, platformJdbc, new JsonUtils(new ObjectMapper()),
        new TransactionTemplate(new DataSourceTransactionManager(platformJdbc.getDataSource())));
    refreshService = new CodeReviewMatchModeLegacyRefreshService(configService, syncService, switchService);
    syncService.syncSingleMergeRequest("cc", TARGET_IID);
    syncService.syncSingleMergeRequest("cc", 22274L);
    syncService.syncSingleMergeRequest("dgm", TARGET_IID);
    syncService.syncSingleMergeRequest("dgm", 22274L);
  }

  @AfterEach
  void stopLegacyServer() {
    if (legacyServer != null) {
      legacyServer.stop(0);
    }
  }

  @ParameterizedTest
  @CsvSource({"dgm,false,2", "dgm,true,2", "cc,false,1", "cc,true,1"})
  void test_refresh_updates_selected_source_and_removes_only_target_scan_illegal_rows(
      String source, boolean formalImport, int expectedRows) {
    String otherSource = "dgm".equals(source) ? "cc" : "dgm";
    JdbcTemplate selectedJdbc = "dgm".equals(source) ? dgmJdbc : ccJdbc;
    JdbcTemplate otherJdbc = "dgm".equals(source) ? ccJdbc : dgmJdbc;
    List<Map<String, Object>> untouchedLocalRows = platformJdbc.queryForList("""
        select * from code_review_match_mode_records
         where source_instance <> ? or merge_request_iid <> ? order by id
        """, source, TARGET_IID);
    assertThat(illegalIids(source)).contains(TARGET_IID, 22274L);
    if (formalImport) {
      // 转正式入口复用同一老源刷新协议，即使兼容模式已关闭仍可更新兼容中间数据。
      when(switchService.isEnabled()).thenReturn(false);
    }

    int rows = formalImport
        ? refreshService.refreshOneForFormalImport(source, TARGET_IID)
        : refreshService.refreshOne(source, TARGET_IID);

    assertThat(rows).isEqualTo(expectedRows);
    assertThat(scanResults(selectedJdbc, TARGET_IID)).hasSize(expectedRows).containsOnly("通过");
    assertThat(scanResults(otherJdbc, TARGET_IID)).containsOnly(ILLEGAL_SCAN_RESULT);
    assertThat(scanResults(selectedJdbc, 22274L)).containsOnly(ILLEGAL_SCAN_RESULT);
    assertThat(platformJdbc.queryForList("""
        select * from code_review_match_mode_records
         where source_instance <> ? or merge_request_iid <> ? order by id
        """, source, TARGET_IID)).isEqualTo(untouchedLocalRows);
    assertThat(platformJdbc.queryForList("""
        select bug_count_result from code_review_match_mode_records
         where source_instance = ? and merge_request_iid = ?
        """, String.class, source, TARGET_IID)).hasSize(expectedRows).containsOnly("通过");
    assertThat(illegalIids(source)).containsExactly(22274L);
    assertThat(illegalIids(otherSource)).contains(TARGET_IID, 22274L);
  }

  private List<Long> illegalIids(String source) {
    CodeReviewIllegalRecordQueryRequest request = new CodeReviewIllegalRecordQueryRequest(
        null, "dgm".equals(source) ? "DGM" : "CrownCAD", null, null, null, null,
        "merge_request", null, null, null, ILLEGAL_SCAN_RESULT, null, null, source, null,
        1, 20, "mergeRequestIid", "asc", null);
    return recordLoader.loadDefaultIllegalPage(new CodeReviewIllegalRecordSourcePageQuery(
        request, null, 1, 20, "mergeRequestIid", "asc")).records().stream()
        .map(row -> row.mergeRequestIid().longValue()).toList();
  }

  private List<String> scanResults(JdbcTemplate jdbc, long iid) {
    return jdbc.queryForList(
        "select bug_count_result from spider_crowncad_data where issuable_reference = ? order by id",
        String.class, String.valueOf(iid));
  }

  private String mysqlUrl(String database) {
    return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
        + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
  }

  private void insertLegacyRow(JdbcTemplate jdbc, int id, String repository, long iid, String reviewer) {
    jdbc.update("""
        insert into spider_crowncad_data (
          id, name, issuable_reference, assignee, bug_count_result
        ) values (?, ?, ?, ?, ?)
        """, id, repository, String.valueOf(iid), reviewer, ILLEGAL_SCAN_RESULT);
  }

  private void createLegacyTable(JdbcTemplate jdbc) {
    jdbc.execute("""
        create table spider_crowncad_data (
          id bigint primary key,
          name varchar(100),
          code_walkthrough_date datetime default '2026-10-10 10:00:00',
          status varchar(30) default 'MERGED',
          module_name varchar(100) default '工具',
          project_name varchar(100) default 'CC2027R1&R2',
          issuable_reference varchar(30),
          merge_request_title varchar(255) default '测试走查',
          author varchar(100) default '被走查人',
          assignee varchar(100),
          assigneed varchar(100) default '被指派人',
          merged_time varchar(30) default '2026-10-10 10:30:00',
          merged_local_date_time datetime default '2026-10-10 10:30:00',
          merged_user_name varchar(100) default '合并人',
          code_walkthrough_duration int default 20,
          added_line int default 711,
          deleted_line int default 0,
          code_specification_count int default 0,
          code_logic_specification_count int default 0,
          performance_specification_count int default 0,
          design_specification_count int default 0,
          other_specification_count int default 0,
          defect_count int default 5,
          code_walkthrough_speed_loc int default 2133,
          code_walkthrough_speed_kloc decimal(12,4) default 2.133,
          code_walkthrough_defect_density decimal(12,4) default 7.0323,
          code_walkthrough_efficiency decimal(12,4) default 15,
          target_branch varchar(50) default 'dev',
          sonar_qube_result varchar(100) default '扫描完成',
          commit_count int default 1,
          commit_rate int default 1,
          annotation_rate double default 21.10,
          annotation_rate_result varchar(100) default '通过',
          bug_count int default 2,
          bug_count_result varchar(100),
          function_name varchar(100) default '工具',
          ct_code_line_count int default 711
        )
        """);
  }
}
