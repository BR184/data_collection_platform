package com.data.collection.platform.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import com.data.collection.platform.service.FactTaskExecutionContext;
import com.data.collection.platform.service.FactTaskExecutionGuard;
import com.data.collection.platform.service.FactTaskLeaseLostException;
import com.data.collection.platform.service.IssueFactPersistenceService;
import com.data.collection.platform.service.MergeRequestFactPersistenceService;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F4 清理固定负载基准：{@code deleteFactsNotInSnapshot} 在真实索引集与固定规模下的成本与原子性。
 *
 * <p>方案 F4 第 5 条要求固定负载至少 50 万事实、实际索引集、不同删除比例、空快照与高子行数，
 * 并分别记录物化耗时、最大单批耗时、总耗时、锁与回滚耗时。本基准用 Flyway 跑完全部迁移，
 * 因此表结构、唯一约束与全部既有搜索索引（含 GIN trgm）都是生产形状，删除语句的索引维护成本真实。
 *
 * <p>度量口径：
 *
 * <ul>
 *   <li>快照装载：把来源快照身份写入会话级临时表。
 *   <li>物化：把待删除业务键物化到 {@code *_delete_ids} 临时表。
 *   <li>最大单批：单个 2000 键批次内「截断批次表 + 装载批次键 + 删除从属行 + 删除父行」的合计耗时。
 *   <li>总耗时：事务内调用 {@code deleteFactsNotInSnapshot} 的墙钟时间。
 *   <li>锁窗口：包裹该清理的单个事务从开始到提交/回滚返回的墙钟时间，即本次清理持锁的上界。
 *   <li>回滚耗时：注入执行权丢失后整个事务回滚的墙钟时间，并断言半次清理不落库。
 * </ul>
 *
 * <p>数据分布边界：种子只填充被 btree 索引覆盖的业务列，{@code search_*}/{@code *_search_*} 等
 * 仅被 GIN trgm 索引覆盖的文本列保持 NULL，因此这些 trigram 索引在本夹具中不产生条目，
 * 其维护成本未被纳入。本基准数值只对「本机 Testcontainers + 该分布」成立；内网 300 万级、
 * 文本高填充的真实容量必须在目标环境实测，不得据此外推。
 *
 * <p>该基准带 {@code benchmark} 标签，默认快速套件与 golden-baseline 门禁都会排除它，
 * 仅通过 {@code mvn test -Pbenchmark} 显式运行。
 */
@Tag("benchmark")
class FactCleanupLoadBenchmarkTest {
  private static final String SOURCE_INSTANCE = "bench";
  private static final String CONTROL_INSTANCE = "bench-other";

  /** 固定负载：50 万议题事实。 */
  private static final int ISSUE_FACT_ROWS = 500_000;
  /** 高子行数：1000 个议题各挂 20 条客户成员，共 2 万子行。 */
  private static final int CUSTOMER_MEMBER_ISSUES = 1_000;
  private static final int CUSTOMERS_PER_ISSUE = 20;
  /** 同表内的对照来源：清理不得触碰它。 */
  private static final int CONTROL_ROWS = 1_000;
  private static final int MR_FACT_ROWS = 100_000;
  private static final int COMMITS_PER_MR = 3;
  /** 与生产常量一致的清理批次大小，用于推算期望批次数。 */
  private static final int CLEANUP_BATCH_SIZE = 2_000;

  private static final String KEEP_60_PERCENT = "fact.issue_id % 5 <= 2";
  private static final String KEEP_95_PERCENT = "fact.issue_id % 20 <> 0";
  private static final String KEEP_NONE = "1 = 0";
  private static final String KEEP_40_PERCENT = "fact.merge_request_id % 5 <= 1";

  private static final Path REPORT_PATH =
      Path.of("target", "benchmark", "fact-cleanup-benchmark-report.txt");
  private static final List<String> REPORT = new ArrayList<>();

  private static PostgresIntegrationTestDatabase database;
  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;
  private static TransactionTemplate transactionTemplate;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_cleanup_benchmark");
    DriverManagerDataSource provided = (DriverManagerDataSource) database.dataSource();
    String url =
        provided.getUrl()
            + (provided.getUrl().contains("?") ? "&" : "?")
            + "currentSchema=qaflex_test,public";
    dataSource = new DriverManagerDataSource(url, provided.getUsername(), provided.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas("qaflex_test")
        .defaultSchema("qaflex_test")
        .createSchemas(true)
        .load()
        .migrate();
    report("=== F4 事实清理固定负载基准 ===");
    report("部署形态：postgres:16-alpine（隔离实例）+ Flyway 全量迁移（真实表结构与全量索引集）");
    report(
        "固定负载：issue_fact="
            + ISSUE_FACT_ROWS
            + "（实例 "
            + SOURCE_INSTANCE
            + "）+ issue_fact_customer_members="
            + (CUSTOMER_MEMBER_ISSUES * CUSTOMERS_PER_ISSUE)
            + "（"
            + CUSTOMER_MEMBER_ISSUES
            + " 议题 × "
            + CUSTOMERS_PER_ISSUE
            + "）+ merge_request_fact="
            + MR_FACT_ROWS
            + " + merge_request_commit_fact="
            + (MR_FACT_ROWS * COMMITS_PER_MR)
            + " + 对照实例 "
            + CONTROL_INSTANCE
            + "="
            + CONTROL_ROWS);
    report("分布限制：仅 btree 索引列有值；trgm 文本列留空，故 GIN trgm 索引维护未纳入本次测量。");
    report("内网 300 万级与高文本填充容量须在目标环境实测，本基准不外推。");
  }

  @AfterAll
  static void tearDown() {
    writeReport();
    if (database != null) {
      database.close();
    }
  }

  @Test
  void test_fixed_load_issue_cleanup_records_cost_and_atomic_rollback() {
    seedIssueLoad();
    long seededParents = countIssueFacts(SOURCE_INSTANCE);
    long seededChildren = countIssueChildren(SOURCE_INSTANCE);
    assertThat(seededParents).isEqualTo(ISSUE_FACT_ROWS);
    assertThat(seededChildren).isEqualTo((long) CUSTOMER_MEMBER_ISSUES * CUSTOMERS_PER_ISSUE);
    long expectedBatches = (seededParents + CLEANUP_BATCH_SIZE - 1) / CLEANUP_BATCH_SIZE;

    // 回滚探针：让半数批次成功后再注入执行权丢失，测量回滚成本并验证半次清理不落库。
    int allowedBatches = (int) Math.max(1L, expectedBatches / 2);
    InstrumentedJdbcTemplate rollbackTemplate = new InstrumentedJdbcTemplate(dataSource);
    IssueFactPersistenceService rollbackService =
        new IssueFactPersistenceService(
            mock(IssueFactMapper.class),
            null,
            rollbackTemplate,
            new FailingGuard(rollbackTemplate, allowedBatches));
    long rollbackStart = System.nanoTime();
    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        rollbackService.deleteFactsNotInSnapshot(
                            "GITLAB", SOURCE_INSTANCE, List.of())))
        .isInstanceOf(FactTaskLeaseLostException.class);
    long rollbackWindowNanos = System.nanoTime() - rollbackStart;
    assertThat(countIssueFacts(SOURCE_INSTANCE))
        .as("执行权在清理中途丢失时必须整体回滚，不得留下半次清理")
        .isEqualTo(seededParents);
    assertThat(countIssueChildren(SOURCE_INSTANCE))
        .as("从属关系行同样必须随父事实一起回滚")
        .isEqualTo(seededChildren);
    assertThat(countIssueFacts(CONTROL_INSTANCE)).isEqualTo(CONTROL_ROWS);
    report(
        "--- 原子性：ISSUE 清理注入执行权丢失（放行 "
            + allowedBatches
            + "/"
            + expectedBatches
            + " 批后失败）---");
    report("  回滚耗时（事务开始→回滚返回）ms=" + millis(rollbackWindowNanos));
    report(
        "  断言：父事实 "
            + seededParents
            + "、客户成员 "
            + seededChildren
            + " 均原样保留；对照实例 "
            + CONTROL_ROWS
            + " 未受影响");

    // 不同删除比例 + 空快照：每次都从完整固定负载重新开始，保证场景互相独立。
    runIssueScenario("删除比例 40%（保留 60%）", KEEP_60_PERCENT, false, expectedBatches);
    seedIssueLoad();
    runIssueScenario("删除比例 5%（保留 95%）", KEEP_95_PERCENT, false, expectedBatches);
    seedIssueLoad();
    runIssueScenario("空快照（删除 100%）", KEEP_NONE, true, expectedBatches);
  }

  @Test
  void test_fixed_load_merge_request_cleanup_records_cost_with_child_relations() {
    seedMergeRequestLoad();
    long seededMr = countMergeRequestFacts(SOURCE_INSTANCE);
    long seededCommits = countMergeRequestCommits(SOURCE_INSTANCE);
    assertThat(seededMr).isEqualTo(MR_FACT_ROWS);
    assertThat(seededCommits).isEqualTo((long) MR_FACT_ROWS * COMMITS_PER_MR);
    long expectedMrBatches = (seededMr + CLEANUP_BATCH_SIZE - 1) / CLEANUP_BATCH_SIZE;

    long expectedMr = countMergeRequestFactsRetained(KEEP_40_PERCENT);
    long expectedCommits = countMergeRequestCommitsRetained(KEEP_40_PERCENT);
    CleanupMetrics metrics =
        runMergeRequestCleanup(mrSnapshot(KEEP_40_PERCENT), mrCommitSnapshot(KEEP_40_PERCENT));
    assertThat(countMergeRequestFacts(SOURCE_INSTANCE)).isEqualTo(expectedMr);
    assertThat(countMergeRequestCommits(SOURCE_INSTANCE)).isEqualTo(expectedCommits);
    assertThat(countMergeRequestFacts(CONTROL_INSTANCE)).isEqualTo(CONTROL_ROWS);
    assertThat(countMergeRequestCommits(CONTROL_INSTANCE)).isEqualTo(CONTROL_ROWS);
    reportMerge("删除比例 60%（父保留 40%，提交关系随父保留）", metrics, expectedMr, expectedCommits);

    // 空提交快照：父事实全部保留，旧提交关系必须被删尽。
    seedMergeRequestLoad();
    List<MergeRequestFact> allMr = mrSnapshot("true");
    assertThat(allMr).hasSize(MR_FACT_ROWS);
    CleanupMetrics emptyCommitMetrics = runMergeRequestCleanup(allMr, List.of());
    assertThat(countMergeRequestFacts(SOURCE_INSTANCE)).isEqualTo(MR_FACT_ROWS);
    assertThat(countMergeRequestCommits(SOURCE_INSTANCE))
        .as("空提交关系快照必须删除相应旧提交关系")
        .isZero();
    assertThat(countMergeRequestFacts(CONTROL_INSTANCE)).isEqualTo(CONTROL_ROWS);
    assertThat(countMergeRequestCommits(CONTROL_INSTANCE)).isEqualTo(CONTROL_ROWS);
    reportMerge("空提交快照（父全保留，提交关系删尽）", emptyCommitMetrics, MR_FACT_ROWS, 0L);
    report("  期望父批次数（2000 键/批）=" + expectedMrBatches);
  }

  private void runIssueScenario(
      String label, String retentionPredicate, boolean emptySnapshot, long expectedBatches) {
    List<IssueFact> snapshot = emptySnapshot ? List.of() : issueSnapshot(retentionPredicate);
    long expectedParents = countIssueFactsRetained(retentionPredicate);
    long expectedChildren = countIssueChildrenRetained(retentionPredicate);
    CleanupMetrics metrics = runIssueCleanup(snapshot);
    assertThat(countIssueFacts(SOURCE_INSTANCE))
        .as("快照内身份必须保留：" + label)
        .isEqualTo(expectedParents);
    assertThat(countIssueChildren(SOURCE_INSTANCE))
        .as("从属关系行必须与父事实一起保留：" + label)
        .isEqualTo(expectedChildren);
    assertThat(countIssueFacts(CONTROL_INSTANCE))
        .as("其他来源不得被清理：" + label)
        .isEqualTo(CONTROL_ROWS);
    report(
        "--- ISSUE 清理 | "
            + label
            + " | 删除父行 "
            + metrics.deletedParents()
            + " / 期望批次 "
            + expectedBatches
            + " ---");
    report(
        "  快照装载 ms="
            + millis(metrics.snapshotLoadNanos())
            + " 物化 ms="
            + millis(metrics.materializeNanos())
            + " 临时表建立 ms="
            + millis(metrics.setupNanos())
            + " 行数盘点 ms="
            + millis(metrics.countNanos()));
    report(
        "  批次键装载 ms="
            + millis(metrics.batchKeysNanos())
            + " 批次删除 ms="
            + millis(metrics.batchDeleteNanos())
            + " 批次数="
            + metrics.batchCount()
            + " 最大单批 ms="
            + millis(metrics.maxBatchNanos()));
    report(
        "  单批最大从属行数="
            + metrics.maxChildRowsInBatch()
            + "（2000 父键不等于最多 2000 行）"
            + " 删除父行="
            + metrics.deletedParents()
            + " 删除从属行="
            + metrics.deletedChildRows());
    report(
        "  总耗时 ms="
            + millis(metrics.totalNanos())
            + " 锁窗口 ms="
            + millis(metrics.lockWindowNanos()));
  }

  private void reportMerge(
      String label, CleanupMetrics metrics, long expectedMr, long expectedCommits) {
    report("--- MR 清理 | " + label + " ---");
    report(
        "  快照装载 ms="
            + millis(metrics.snapshotLoadNanos())
            + " 物化 ms="
            + millis(metrics.materializeNanos())
            + " 临时表建立 ms="
            + millis(metrics.setupNanos())
            + " 行数盘点 ms="
            + millis(metrics.countNanos()));
    report(
        "  批次键装载 ms="
            + millis(metrics.batchKeysNanos())
            + " 批次删除 ms="
            + millis(metrics.batchDeleteNanos())
            + " 批次数="
            + metrics.batchCount()
            + " 最大单批 ms="
            + millis(metrics.maxBatchNanos())
            + " 单批最大从属行数="
            + metrics.maxChildRowsInBatch());
    report(
        "  总耗时 ms="
            + millis(metrics.totalNanos())
            + " 锁窗口 ms="
            + millis(metrics.lockWindowNanos())
            + " 删除父行="
            + metrics.deletedParents()
            + " 删除从属行="
            + metrics.deletedChildRows());
    report("  断言：父事实残留=" + expectedMr + "、提交关系残留=" + expectedCommits + "、对照实例未受影响");
  }

  private CleanupMetrics runIssueCleanup(List<IssueFact> snapshot) {
    InstrumentedJdbcTemplate instrumented = new InstrumentedJdbcTemplate(dataSource);
    IssueFactPersistenceService service =
        new IssueFactPersistenceService(
            mock(IssueFactMapper.class),
            null,
            instrumented,
            new FactTaskExecutionGuard(instrumented, new FactTaskExecutionContext()));
    long parentsBefore = countIssueFacts(SOURCE_INSTANCE);
    long childrenBefore = countIssueChildren(SOURCE_INSTANCE);
    long[] inside = new long[1];
    long windowStart = System.nanoTime();
    transactionTemplate.executeWithoutResult(
        status -> {
          long start = System.nanoTime();
          service.deleteFactsNotInSnapshot("GITLAB", SOURCE_INSTANCE, snapshot);
          inside[0] = System.nanoTime() - start;
        });
    long lockWindowNanos = System.nanoTime() - windowStart;
    return instrumented.metrics(
        inside[0],
        lockWindowNanos,
        parentsBefore - countIssueFacts(SOURCE_INSTANCE),
        childrenBefore - countIssueChildren(SOURCE_INSTANCE));
  }

  private CleanupMetrics runMergeRequestCleanup(
      List<MergeRequestFact> mrSnapshot, List<MergeRequestCommitFact> commitSnapshot) {
    InstrumentedJdbcTemplate instrumented = new InstrumentedJdbcTemplate(dataSource);
    MergeRequestFactPersistenceService service =
        new MergeRequestFactPersistenceService(
            mock(MergeRequestFactMapper.class),
            instrumented,
            new FactTaskExecutionGuard(instrumented, new FactTaskExecutionContext()));
    long mrBefore = countMergeRequestFacts(SOURCE_INSTANCE);
    long commitsBefore = countMergeRequestCommits(SOURCE_INSTANCE);
    long[] inside = new long[1];
    long windowStart = System.nanoTime();
    transactionTemplate.executeWithoutResult(
        status -> {
          long start = System.nanoTime();
          service.deleteFactsNotInSnapshot("GITLAB", SOURCE_INSTANCE, mrSnapshot, commitSnapshot);
          inside[0] = System.nanoTime() - start;
        });
    long lockWindowNanos = System.nanoTime() - windowStart;
    return instrumented.metrics(
        inside[0],
        lockWindowNanos,
        mrBefore - countMergeRequestFacts(SOURCE_INSTANCE),
        commitsBefore - countMergeRequestCommits(SOURCE_INSTANCE));
  }

  private void seedIssueLoad() {
    jdbcTemplate.execute("truncate table issue_fact, issue_fact_customer_members");
    jdbcTemplate.update(
        """
        insert into issue_fact(
          source_system, source_instance, project_id, issue_id, issue_iid, title,
          issue_state, severity_level, priority_level, module_name, testing_phase,
          is_excluded, is_fixed, is_illegal, deleted, updated_at_source)
        select 'GITLAB', ?, 100 + (g / 10000), g, g, 'title ' || g,
               case when g % 3 = 0 then 'closed' else 'opened' end,
               'SEV' || (g % 4), 'P' || (g % 3), 'module-' || (g % 97), 'phase-' || (g % 11),
               false, (g % 7 = 0), (g % 13 = 0), false,
               timestamp '2026-01-01 00:00:00' + (g || ' seconds')::interval
          from generate_series(1, ?) as g
        """,
        SOURCE_INSTANCE,
        ISSUE_FACT_ROWS);
    jdbcTemplate.update(
        """
        insert into issue_fact_customer_members(
          source_system, source_instance, project_id, issue_id, customer_name)
        select 'GITLAB', ?, 100, issue_id, 'customer-' || member_no
          from generate_series(1, ?) as issue_id
         cross join generate_series(1, ?) as member_no
        """,
        SOURCE_INSTANCE,
        CUSTOMER_MEMBER_ISSUES,
        CUSTOMERS_PER_ISSUE);
    jdbcTemplate.update(
        """
        insert into issue_fact(source_system, source_instance, project_id, issue_id, issue_iid)
        select 'GITLAB', ?, 999, g, g from generate_series(1, ?) as g
        """,
        CONTROL_INSTANCE,
        CONTROL_ROWS);
  }

  private void seedMergeRequestLoad() {
    jdbcTemplate.execute("truncate table merge_request_fact, merge_request_commit_fact");
    jdbcTemplate.update(
        """
        insert into merge_request_fact(
          source_system, source_instance, project_id, merge_request_id, merge_request_iid,
          title, merge_request_state, deleted)
        select 'GITLAB', ?, 200 + (g / 10000), g, g, 'mr ' || g, 'merged', false
          from generate_series(1, ?) as g
        """,
        SOURCE_INSTANCE,
        MR_FACT_ROWS);
    jdbcTemplate.update(
        """
        insert into merge_request_commit_fact(
          source_system, source_instance, project_id, merge_request_id, merge_request_iid,
          commit_sha, committed_at_source)
        select 'GITLAB', ?, 200 + (g / 10000), g, g, 'sha-' || g || '-' || c,
               timestamp '2026-01-01 00:00:00' + (g || ' seconds')::interval
          from generate_series(1, ?) as g
         cross join generate_series(1, ?) as c
        """,
        SOURCE_INSTANCE,
        MR_FACT_ROWS,
        COMMITS_PER_MR);
    jdbcTemplate.update(
        """
        insert into merge_request_fact(
          source_system, source_instance, project_id, merge_request_id, merge_request_iid)
        select 'GITLAB', ?, 999, g, g from generate_series(1, ?) as g
        """,
        CONTROL_INSTANCE,
        CONTROL_ROWS);
    jdbcTemplate.update(
        """
        insert into merge_request_commit_fact(
          source_system, source_instance, project_id, merge_request_id, merge_request_iid,
          commit_sha, committed_at_source)
        select 'GITLAB', ?, 999, g, g, 'ctl-' || g, timestamp '2026-01-01 00:00:00'
          from generate_series(1, ?) as g
        """,
        CONTROL_INSTANCE,
        CONTROL_ROWS);
  }

  private List<IssueFact> issueSnapshot(String retentionPredicate) {
    return jdbcTemplate.query(
        "select fact.project_id, fact.issue_id from issue_fact fact"
            + " where fact.source_instance = ? and "
            + retentionPredicate,
        (rs, rowNum) -> {
          IssueFact fact = new IssueFact();
          fact.setProjectId(rs.getLong(1));
          fact.setIssueId(rs.getLong(2));
          return fact;
        },
        SOURCE_INSTANCE);
  }

  private List<MergeRequestFact> mrSnapshot(String retentionPredicate) {
    return jdbcTemplate.query(
        "select fact.project_id, fact.merge_request_id from merge_request_fact fact"
            + " where fact.source_instance = ? and "
            + retentionPredicate,
        (rs, rowNum) -> {
          MergeRequestFact fact = new MergeRequestFact();
          fact.setProjectId(rs.getLong(1));
          fact.setMergeRequestId(rs.getLong(2));
          return fact;
        },
        SOURCE_INSTANCE);
  }

  private List<MergeRequestCommitFact> mrCommitSnapshot(String retentionPredicate) {
    return jdbcTemplate.query(
        "select fact.project_id, fact.merge_request_id, fact.merge_request_iid, fact.commit_sha,"
            + " fact.committed_at_source from merge_request_commit_fact fact"
            + " where fact.source_instance = ? and "
            + retentionPredicate,
        (rs, rowNum) ->
            new MergeRequestCommitFact(
                "GITLAB",
                SOURCE_INSTANCE,
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getString(4),
                rs.getTimestamp(5).toLocalDateTime()),
        SOURCE_INSTANCE);
  }

  private long countIssueFacts(String sourceInstance) {
    return scalarLong("select count(*) from issue_fact where source_instance = ?", sourceInstance);
  }

  private long countIssueChildren(String sourceInstance) {
    return scalarLong(
        "select count(*) from issue_fact_customer_members where source_instance = ?",
        sourceInstance);
  }

  private long countIssueFactsRetained(String retentionPredicate) {
    return scalarLong(
        "select count(*) from issue_fact fact where fact.source_instance = ? and "
            + retentionPredicate,
        SOURCE_INSTANCE);
  }

  private long countIssueChildrenRetained(String retentionPredicate) {
    return scalarLong(
        "select count(*) from issue_fact_customer_members member"
            + " where member.source_instance = ? and exists ("
            + "select 1 from issue_fact fact"
            + " where fact.source_instance = member.source_instance"
            + " and fact.project_id = member.project_id"
            + " and fact.issue_id = member.issue_id and "
            + retentionPredicate
            + ")",
        SOURCE_INSTANCE);
  }

  private long countMergeRequestFacts(String sourceInstance) {
    return scalarLong(
        "select count(*) from merge_request_fact where source_instance = ?", sourceInstance);
  }

  private long countMergeRequestCommits(String sourceInstance) {
    return scalarLong(
        "select count(*) from merge_request_commit_fact where source_instance = ?",
        sourceInstance);
  }

  private long countMergeRequestFactsRetained(String retentionPredicate) {
    return scalarLong(
        "select count(*) from merge_request_fact fact where fact.source_instance = ? and "
            + retentionPredicate,
        SOURCE_INSTANCE);
  }

  private long countMergeRequestCommitsRetained(String retentionPredicate) {
    return scalarLong(
        "select count(*) from merge_request_commit_fact fact where fact.source_instance = ? and "
            + retentionPredicate,
        SOURCE_INSTANCE);
  }

  private long scalarLong(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private static double millis(long nanos) {
    return Math.round(nanos / 1000.0) / 1000.0;
  }

  private static void report(String line) {
    REPORT.add(line);
    System.out.println("[F4-BENCH] " + line);
  }

  private static void writeReport() {
    if (REPORT.isEmpty()) {
      return;
    }
    try {
      Files.createDirectories(REPORT_PATH.getParent());
      Files.write(REPORT_PATH, REPORT, StandardCharsets.UTF_8);
      System.out.println("[F4-BENCH] 报告已写入 " + REPORT_PATH.toAbsolutePath());
    } catch (IOException error) {
      System.out.println("[F4-BENCH] 报告写入失败：" + error.getMessage());
    }
  }

  /**
   * 记录每条 JDBC 调用的耗时与影响行数，并按 SQL 语义分类，供基准聚合出物化、批次与锁的分解成本。
   *
   * <p>只覆盖 {@code deleteFactsNotInSnapshot} 实际使用的外层调用；内部委托不重复计时。
   */
  private static final class InstrumentedJdbcTemplate extends JdbcTemplate {
    private static final String KIND_SNAPSHOT_LOAD = "snapshot-load";
    private static final String KIND_MATERIALIZE = "materialize";
    private static final String KIND_TEMP_SETUP = "temp-setup";
    private static final String KIND_BATCH_START = "batch-start";
    private static final String KIND_BATCH_KEYS = "batch-keys";
    private static final String KIND_CHILD_DELETE = "child-delete";
    private static final String KIND_PARENT_DELETE = "parent-delete";
    private static final String KIND_COUNT = "count";
    private static final String KIND_OTHER = "other";

    private final List<Event> events = new ArrayList<>();

    InstrumentedJdbcTemplate(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    public void execute(String sql) {
      long start = System.nanoTime();
      super.execute(sql);
      capture(classify(sql), System.nanoTime() - start, 0);
    }

    @Override
    public int update(String sql) {
      long start = System.nanoTime();
      int rows = super.update(sql);
      capture(classify(sql), System.nanoTime() - start, rows);
      return rows;
    }

    @Override
    public int update(String sql, Object... args) {
      long start = System.nanoTime();
      int rows = super.update(sql, args);
      capture(classify(sql), System.nanoTime() - start, rows);
      return rows;
    }

    @Override
    public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
      long start = System.nanoTime();
      int[] rows = super.batchUpdate(sql, batchArgs);
      capture(classify(sql), System.nanoTime() - start, rows.length);
      return rows;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType) {
      long start = System.nanoTime();
      T value = super.queryForObject(sql, requiredType);
      capture(classify(sql), System.nanoTime() - start, 0);
      return value;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      long start = System.nanoTime();
      T value = super.queryForObject(sql, requiredType, args);
      capture(classify(sql), System.nanoTime() - start, 0);
      return value;
    }

    private static String classify(String sql) {
      String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
      if (normalized.startsWith("create temp table") && normalized.contains("_delete_ids")) {
        return KIND_MATERIALIZE;
      }
      if (normalized.startsWith("create temp table")) {
        return KIND_TEMP_SETUP;
      }
      if (normalized.startsWith("truncate")) {
        return KIND_BATCH_START;
      }
      if (normalized.startsWith("insert") && normalized.contains("_cleanup_batch")) {
        return KIND_BATCH_KEYS;
      }
      if (normalized.startsWith("insert") && normalized.contains("_snapshot_ids")) {
        return KIND_SNAPSHOT_LOAD;
      }
      if (normalized.startsWith("delete")) {
        return normalized.contains("customer_members") || normalized.contains("commit_fact")
            ? KIND_CHILD_DELETE
            : KIND_PARENT_DELETE;
      }
      if (normalized.startsWith("select count")) {
        return KIND_COUNT;
      }
      return KIND_OTHER;
    }

    private void capture(String kind, long nanos, int rows) {
      events.add(new Event(kind, nanos, rows));
    }

    CleanupMetrics metrics(
        long totalNanos, long lockWindowNanos, long deletedParents, long deletedChildRows) {
      long snapshotLoad = 0L;
      long materialize = 0L;
      long setup = 0L;
      long count = 0L;
      long batchKeys = 0L;
      long batchDelete = 0L;
      long currentBatch = 0L;
      long maxBatch = 0L;
      long currentChildRows = 0L;
      long maxChildRowsInBatch = 0L;
      int batches = 0;
      for (Event event : events) {
        switch (event.kind) {
          case KIND_SNAPSHOT_LOAD -> snapshotLoad += event.nanos;
          case KIND_MATERIALIZE -> materialize += event.nanos;
          case KIND_TEMP_SETUP -> setup += event.nanos;
          case KIND_COUNT -> count += event.nanos;
          case KIND_BATCH_KEYS -> {
            batchKeys += event.nanos;
            currentBatch += event.nanos;
          }
          case KIND_CHILD_DELETE -> {
            batchDelete += event.nanos;
            currentBatch += event.nanos;
            currentChildRows += event.rows;
          }
          case KIND_PARENT_DELETE -> {
            batchDelete += event.nanos;
            currentBatch += event.nanos;
          }
          case KIND_BATCH_START -> {
            if (batches > 0) {
              maxBatch = Math.max(maxBatch, currentBatch);
              maxChildRowsInBatch = Math.max(maxChildRowsInBatch, currentChildRows);
            }
            batches++;
            currentBatch = event.nanos;
            currentChildRows = 0L;
          }
          default -> {
            // 其他语句不计入基准分解。
          }
        }
      }
      if (batches > 0) {
        maxBatch = Math.max(maxBatch, currentBatch);
        maxChildRowsInBatch = Math.max(maxChildRowsInBatch, currentChildRows);
      }
      return new CleanupMetrics(
          snapshotLoad,
          materialize,
          setup,
          count,
          batchKeys,
          batchDelete,
          batches,
          maxBatch,
          maxChildRowsInBatch,
          totalNanos,
          lockWindowNanos,
          deletedParents,
          deletedChildRows);
    }

    private record Event(String kind, long nanos, int rows) {}
  }

  private record CleanupMetrics(
      long snapshotLoadNanos,
      long materializeNanos,
      long setupNanos,
      long countNanos,
      long batchKeysNanos,
      long batchDeleteNanos,
      int batchCount,
      long maxBatchNanos,
      long maxChildRowsInBatch,
      long totalNanos,
      long lockWindowNanos,
      long deletedParents,
      long deletedChildRows) {}

  /** 放行前若干批次后抛出执行权丢失，用于测量中途失去执行权时的完整回滚。 */
  private static final class FailingGuard extends FactTaskExecutionGuard {
    private final int allowedBatches;
    private final AtomicInteger invocations = new AtomicInteger();

    FailingGuard(JdbcTemplate jdbcTemplate, int allowedBatches) {
      super(jdbcTemplate, new FactTaskExecutionContext());
      this.allowedBatches = allowedBatches;
    }

    @Override
    public void requireCurrentTaskAuthorization() {
      if (invocations.incrementAndGet() > allowedBatches) {
        throw new FactTaskLeaseLostException(1L, "基准注入事实任务执行权丢失");
      }
    }
  }
}
