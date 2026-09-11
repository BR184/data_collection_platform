package com.data.collection.platform.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.golden.GoldenBaselineSupport.GoldenHttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 全链路黄金基线套件主入口：双容器恢复冻结夹具 → Flyway → 平台种子 → 真实登录与
 * 全量同步（事实刷新自动跟随）→ 端点快照遍历。
 *
 * <p>仅在 golden-baseline Maven profile 下运行（独立 JVM 一次启动、一次同步摊销全部端点）；
 * 链路产出构成所有端点快照的共享前置状态，任何断言失败都阻断后续快照生成。
 */
@Tag("golden-baseline")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GoldenBaselineChainTest {

  private static final Pattern PATH_VAR_PATTERN = Pattern.compile("\\{([^/}]+)\\}");
  private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

  @LocalServerPort
  private int port;

  private GoldenHttpSession session;
  private long configId;

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    GoldenBaselineSupport.PLATFORM_DB.start();
    GoldenBaselineSupport.startSourceDatabase();
    registry.add("spring.datasource.url", GoldenBaselineSupport::platformJdbcUrl);
    registry.add("spring.datasource.username", GoldenBaselineSupport.PLATFORM_DB::getUsername);
    registry.add("spring.datasource.password", GoldenBaselineSupport.PLATFORM_DB::getPassword);
    registry.add("platform.background-jobs.enabled", () -> "true");
    registry.add("platform.gitlab-mirror.scheduler-enabled", () -> "true");
    registry.add("platform.gitlab-mirror.code-review-metric-enrichment-enabled", () -> "false");
    registry.add("platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled", () -> "false");
    registry.add("platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled", () -> "false");
    registry.add("platform.code-review.match-mode.initial-delay-ms", () -> "3600000");
    registry.add("platform.code-review.match-mode.sync-delay-ms", () -> "3600000");
    registry.add("platform.code-review.match-mode.mongo-initial-delay-ms", () -> "3600000");
    registry.add("platform.code-review.match-mode.mongo-sync-delay-ms", () -> "3600000");
    registry.add("platform.code-review.dgm-project-options.initial-delay-ms", () -> "3600000");
    registry.add("platform.code-review.dgm-project-options.scheduler-delay-ms", () -> "3600000");
    registry.add("platform.bi.cat.initial-delay-ms", () -> "3600000");
    registry.add("platform.bi.cat.scheduler-delay-ms", () -> "3600000");
  }

  @BeforeAll
  void bootstrapChain() throws Exception {
    GoldenBaselineSupport.importPlatformSeed();
    configId = GoldenBaselineSupport.seedSyncConfig();
    session = GoldenHttpSession.login(
        "http://localhost:" + port, "admin", "admin-test-2026");
    GoldenBaselineSupport.triggerFullSyncAndAwait(session, configId);
    captureWriteReplayBaselines();
  }

  /** 写用例执行前捕获不在种子文件中的 REPLAY 表基线（迁移播种/协调器重建/同步产物）。 */
  private static void captureWriteReplayBaselines() throws SQLException {
    List<String> tables = GoldenEndpointCatalog.load().entries().values().stream()
        .filter(entry -> entry.category() == GoldenEndpointCatalog.Category.WRITE)
        .flatMap(entry -> entry.writeCases().stream())
        .flatMap(writeCase -> writeCase.affectedTables().stream())
        .filter(table -> table.restore() == GoldenEndpointCatalog.RestoreMode.REPLAY)
        .map(GoldenEndpointCatalog.AffectedTable::table)
        .distinct()
        .toList();
    GoldenWriteCaseSupport.captureReplayBaselines(tables);
  }

  @Test
  void test_full_sync_chain_populates_ods_and_facts() throws Exception {
    assertThat(GoldenBaselineSupport.countPlatformRows("ods_gitlab_issues"))
        .as("ODS issues 应包含切片全量行").isGreaterThanOrEqualTo(1200L);
    assertThat(GoldenBaselineSupport.countPlatformRows("issue_fact"))
        .as("事实层 issues 应与切片一致").isGreaterThanOrEqualTo(1200L);
  }

  /** 按端点目录展开全部读/选项/导出用例为动态测试，逐一执行快照断言。 */
  @TestFactory
  Stream<DynamicTest> readAndExportSnapshots() throws Exception {
    GoldenEndpointCatalog catalog = GoldenEndpointCatalog.load();
    Map<String, String> vars = resolveGoldenVariables();
    return catalog.entries().values().stream()
        .filter(entry -> entry.category() != GoldenEndpointCatalog.Category.WRITE
            && entry.category() != GoldenEndpointCatalog.Category.EXCLUDED)
        .flatMap(entry -> entry.cases().stream()
            .map(readCase -> DynamicTest.dynamicTest(
                GoldenEndpointCatalog.key(entry.method(), entry.path()) + " :: " + readCase.id(),
                () -> executeSnapshotCase(entry, readCase, vars))));
  }

  /**
   * 按端点目录展开全部写用例：预还原受影响表 → 固定输入执行 → 响应与表状态快照 → 后还原。
   *
   * <p>前后双重还原使写用例与读快照的相对执行顺序无关紧要。</p>
   */
  @TestFactory
  Stream<DynamicTest> writeSnapshots() throws Exception {
    GoldenEndpointCatalog catalog = GoldenEndpointCatalog.load();
    Map<String, String> vars = resolveGoldenVariables();
    return catalog.entries().values().stream()
        .filter(entry -> entry.category() == GoldenEndpointCatalog.Category.WRITE)
        .flatMap(entry -> entry.writeCases().stream()
            .map(writeCase -> DynamicTest.dynamicTest(
                GoldenEndpointCatalog.key(entry.method(), entry.path()) + " :: " + writeCase.id(),
                () -> executeWriteCase(entry, writeCase, vars))));
  }

  private void executeWriteCase(
      GoldenEndpointCatalog.EndpointEntry entry,
      GoldenEndpointCatalog.WriteCase writeCase,
      Map<String, String> vars) throws Exception {
    GoldenCaseRequest request = resolveRequest(
        entry, writeCase.query(), writeCase.body(), vars);
    for (GoldenEndpointCatalog.AffectedTable table : writeCase.affectedTables()) {
      GoldenWriteCaseSupport.restore(table);
    }
    try {
      String responseBody =
          session.exchangeAnyJson(entry.method(), request.path(), request.body());
      String caseSnapshot = GoldenSnapshotSupport.snapshotName(entry, writeCase.id());
      GoldenSnapshotSupport.assertJsonSnapshot(
          caseSnapshot, responseBody, entry.ignorePaths(), entry.ignoreArrayOrder());
      for (GoldenEndpointCatalog.AffectedTable table : writeCase.affectedTables()) {
        String tableSnapshot = caseSnapshot.substring(0, caseSnapshot.length() - ".json".length())
            + "__" + table.table() + ".json";
        GoldenSnapshotSupport.assertJsonSnapshot(
            tableSnapshot, snapshotScopedTable(table, vars), List.of(), List.of());
      }
    } finally {
      for (GoldenEndpointCatalog.AffectedTable table : writeCase.affectedTables()) {
        GoldenWriteCaseSupport.restore(table);
      }
    }
  }

  /** 表状态快照：scope 声明支持 {{变量}}，issue 维度键来自运行期事实行。 */
  private static String snapshotScopedTable(
      GoldenEndpointCatalog.AffectedTable table, Map<String, String> vars) throws SQLException {
    String scope = table.scope() == null ? null : substituteVars(table.scope(), vars);
    return GoldenWriteCaseSupport.snapshotTable(table.table(), scope, table.ignoreColumns());
  }

  private void executeSnapshotCase(
      GoldenEndpointCatalog.EndpointEntry entry,
      GoldenEndpointCatalog.ReadCase readCase,
      Map<String, String> vars) throws Exception {
    GoldenCaseRequest request = resolveCaseRequest(entry, readCase, vars);
    String snapshotName = GoldenSnapshotSupport.snapshotName(entry, readCase.id());
    if (entry.response() == GoldenEndpointCatalog.ResponseKind.EXCEL) {
      GoldenSnapshotSupport.assertExportSnapshot(
          snapshotName, session.getBytes(request.path()), entry.ignorePaths());
      return;
    }
    String responseBody = "POST".equals(entry.method())
        ? session.postRawJson(request.path(), request.body())
        : session.getRawJson(request.path());
    GoldenSnapshotSupport.assertJsonSnapshot(
        snapshotName, responseBody, entry.ignorePaths(), entry.ignoreArrayOrder());
  }

  /** 解析用例的运行期变量、路径变量与查询串，生成实际请求。 */
  private static GoldenCaseRequest resolveRequest(
      GoldenEndpointCatalog.EndpointEntry entry,
      String caseQuery,
      String caseBody,
      Map<String, String> vars) {
    Map<String, String> params = new LinkedHashMap<>();
    if (!caseQuery.isEmpty()) {
      for (String pair : caseQuery.split("&")) {
        int eq = pair.indexOf('=');
        if (eq <= 0) {
          throw new IllegalStateException("case 查询串片段非法: " + pair);
        }
        params.put(pair.substring(0, eq), substituteVars(pair.substring(eq + 1), vars));
      }
    }
    String path = substitutePath(entry.path(), params);
    String query = params.entrySet().stream()
        .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
        .collect(Collectors.joining("&"));
    return new GoldenCaseRequest(
        query.isEmpty() ? path : path + "?" + query,
        caseBody == null ? null : substituteVars(caseBody, vars));
  }

  /** 解析读/导出用例请求（复用通用解析）。 */
  private static GoldenCaseRequest resolveCaseRequest(
      GoldenEndpointCatalog.EndpointEntry entry,
      GoldenEndpointCatalog.ReadCase readCase,
      Map<String, String> vars) {
    return resolveRequest(entry, readCase.query(), readCase.body(), vars);
  }

  private static String substitutePath(String pathTemplate, Map<String, String> params) {
    Matcher matcher = PATH_VAR_PATTERN.matcher(pathTemplate);
    StringBuilder resolved = new StringBuilder();
    while (matcher.find()) {
      String value = params.remove(matcher.group(1));
      if (value == null) {
        throw new IllegalStateException("用例未提供路径变量 " + matcher.group() + "：" + pathTemplate);
      }
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(urlEncode(value)));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  private static String substituteVars(String text, Map<String, String> vars) {
    Matcher matcher = VAR_PATTERN.matcher(text);
    StringBuilder resolved = new StringBuilder();
    while (matcher.find()) {
      String value = vars.get(matcher.group(1));
      if (value == null) {
        throw new IllegalStateException("未知运行期变量 {{" + matcher.group(1) + "}}");
      }
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** 查询基线用例依赖的种子稳定 ID（配置 id 与代表性业务记录 id）。 */
  private Map<String, String> resolveGoldenVariables() throws SQLException {
    Map<String, String> vars = new HashMap<>();
    vars.put("configId", String.valueOf(configId));
    vars.put("recordId", GoldenBaselineSupport.queryScalar("select min(id) from review_records"));
    vars.put("catalogId",
        GoldenBaselineSupport.queryScalar("select min(id) from issue_scope_catalogs"));
    vars.put("groupId", GoldenBaselineSupport.queryScalar("select min(id) from label_groups"));
    vars.put("reviewRecordWithItemsId",
        GoldenBaselineSupport.queryScalar("select min(review_record_id) from review_problem_items"));
    vars.put("problemItemId",
        GoldenBaselineSupport.queryScalar("select min(id) from review_problem_items"));
    String[] issueKey = GoldenBaselineSupport.queryScalar(
            "select source_instance || '|' || project_id || '|' || issue_iid || '|' || id "
                + "from issue_fact order by source_instance, project_id, issue_iid limit 1")
        .split("\\|", -1);
    vars.put("issueKeySource", issueKey[0]);
    vars.put("issueKeyProjectId", issueKey[1]);
    vars.put("issueKeyIid", issueKey[2]);
    vars.put("issueKeyDbId", issueKey[3]);
    vars.put("sourceDbHost", GoldenBaselineSupport.GITLAB_SOURCE.getHost());
    vars.put("sourceDbPort",
        String.valueOf(GoldenBaselineSupport.GITLAB_SOURCE.getMappedPort(5432)));
    // 写用例目录：组最多的目录保证 group-order 用例有完整 id 列表可重排
    String[] scopeCatalog = GoldenBaselineSupport.queryScalar(
            "select c.id || '|' || c.project_id || '|' || c.project_name || '|' || c.dimension "
                + "from issue_scope_catalogs c "
                + "join issue_scope_groups g on g.catalog_id = c.id "
                + "group by c.id, c.project_id, c.project_name, c.dimension "
                + "order by count(*) desc, c.id limit 1")
        .split("\\|", -1);
    vars.put("scopeCatalogId", scopeCatalog[0]);
    vars.put("scopeCatalogProjectId", scopeCatalog[1]);
    vars.put("scopeCatalogProjectName", scopeCatalog[2]);
    vars.put("scopeCatalogDimension", scopeCatalog[3]);
    vars.put("scopeGroupOrderIds", GoldenBaselineSupport.queryScalar(
        "select coalesce(string_agg(id::text, ',' order by id), '') from issue_scope_groups "
            + "where catalog_id = " + scopeCatalog[0]));
    // 成员最多的组保证 member-order 用例有完整 id 列表可重排
    String[] scopeGroup = GoldenBaselineSupport.queryScalar(
            "select g.catalog_id || '|' || g.business_key || '|' || g.id || '|' || "
                + "coalesce(string_agg(m.id::text, ',' order by m.id), '') "
                + "from issue_scope_groups g "
                + "left join issue_scope_members m on m.group_id = g.id "
                + "group by g.id, g.catalog_id, g.business_key "
                + "order by count(m.id) desc, g.id limit 1")
        .split("\\|", -1);
    vars.put("scopeGroupCatalogId", scopeGroup[0]);
    vars.put("scopeGroupBusinessKey", scopeGroup[1]);
    vars.put("scopeGroupId", scopeGroup[2]);
    vars.put("scopeMemberOrderIds", scopeGroup[3]);
    String[] scopeMember = GoldenBaselineSupport.queryScalar(
            "select catalog_id || '|' || group_id || '|' || id || '|' || source_value "
                + "from issue_scope_members order by id limit 1")
        .split("\\|", -1);
    vars.put("scopeMemberCatalogId", scopeMember[0]);
    vars.put("scopeMemberGroupId", scopeMember[1]);
    vars.put("scopeMemberId", scopeMember[2]);
    vars.put("scopeMemberSourceValue", scopeMember[3]);
    vars.put("settingsUpdatedAt", GoldenBaselineSupport.queryScalar(
        "select replace(cast(updated_at as text), ' ', 'T') "
            + "from code_review_match_mode_db_settings where id = 1"));
    // BI 产品版本 id：与 BiPlatformProductVersionAdapter.catalog() 的 defaultId 同口径
    //（CrownCAD 项目 9 + TESTING_PHASE 维度 + 启用组，按 sort_order,id 取首个）。
    // coalesce 兜底 0：夹具若缺该组也不致整链在变量解析阶段崩溃；届时 BI 数据页返回
    // 确定性错误响应，由 update 后的 git diff 审阅拦截并改判 EXCLUDED。
    vars.put("biProductVersionId", GoldenBaselineSupport.queryScalar(
        "select coalesce((select g.id from issue_scope_catalogs c "
            + "join issue_scope_groups g on g.catalog_id = c.id and g.enabled = true "
            + "where c.project_id = 9 and c.dimension = 'TESTING_PHASE' and c.enabled = true "
            + "order by g.sort_order asc, g.id asc limit 1), 0)"));
    return vars;
  }

  /** 解析后的用例请求：完整路径（含查询串）与请求体。 */
  private record GoldenCaseRequest(String path, String body) {}
}
