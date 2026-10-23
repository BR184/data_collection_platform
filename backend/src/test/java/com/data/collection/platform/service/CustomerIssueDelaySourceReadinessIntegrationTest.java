package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 写回资格的正证据与等待边界（真实 PostgreSQL）。
 *
 * <p>锁定：无关运行先结束不能证明绑定镜像成功；PARTIAL_SUCCESS、缺表都不放行；无发布状态、
 * 未结算全量意图、未发布根、事件历史不完整、在途改写相关输入的运行都必须等待。
 */
@SpringBootTest
class CustomerIssueDelaySourceReadinessIntegrationTest {
  private static final String SOURCE = "default";
  private static final List<String> PRE_TABLES =
      List.of("issues", "notes", "label_links", "labels");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueDelaySourceReadinessService readinessService;

  private long configId;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    cleanUp();
    configId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(
                name, source_instance, source_enabled, auto_sync_enabled, db_name,
                db_username, db_password, delay_label_writeback_enabled,
                web_base_url, api_token)
            values ('delay-readiness-it', ?, true, false, 'mirror', 'reader', 'secret',
                    true, 'http://127.0.0.1', 'token')
            returning id
            """,
            Long.class,
            SOURCE);
    config = new GitlabSyncConfig();
    config.setId(configId);
    config.setSourceInstance(SOURCE);
    config.setSourceEnabled(true);
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void unrelatedRunFinishingFirstCannotProveBoundMirrorSucceeded() {
    long boundRun = insertRun("RUNNING", "{\"sourceTables\":[\"issues\",\"notes\",\"label_links\",\"labels\"]}");
    insertRun("SUCCESS", "{\"sourceTables\":[\"merge_requests\"]}");

    CustomerIssueDelaySourceReadinessService.MirrorCheck check =
        readinessService.checkMirrorRun(config, boundRun, PRE_TABLES);

    assertThat(check.complete()).isFalse();
    assertThat(check.terminal()).isFalse();
    assertThat(check.reason()).contains("尚未终态");
  }

  @Test
  void partialSuccessMirrorIsTerminalButNeverComplete() {
    long run = insertRun("PARTIAL_SUCCESS", "{\"sourceTables\":[\"issues\"]}");
    successTasks(run, PRE_TABLES);

    CustomerIssueDelaySourceReadinessService.MirrorCheck check =
        readinessService.checkMirrorRun(config, run, PRE_TABLES);

    assertThat(check.complete()).isFalse();
    assertThat(check.terminal()).isTrue();
    assertThat(check.reason()).contains("PARTIAL_SUCCESS");
  }

  @Test
  void successWithoutAllRequiredTablesIsNotComplete() {
    long run = insertRun("SUCCESS", "{\"sourceTables\":[\"issues\"]}");
    successTasks(run, List.of("issues", "label_links", "labels"));

    CustomerIssueDelaySourceReadinessService.MirrorCheck check =
        readinessService.checkMirrorRun(config, run, PRE_TABLES);

    assertThat(check.complete()).isFalse();
    assertThat(check.terminal()).isTrue();
    assertThat(check.reason()).contains("notes");
  }

  @Test
  void convergedSourceWithPositiveEvidenceIsReady() {
    ReadyFixture fixture = readyFixture();

    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, fixture.runId(), PRE_TABLES);

    assertThat(readiness.ready()).isTrue();
    assertThat(readiness.factVersion()).isEqualTo(5L);
    assertThat(readiness.reason()).isNull();
  }

  @Test
  void unsettledFullPublicationBlocksWriteback() {
    ReadyFixture fixture = readyFixture();
    jdbcTemplate.update(
        """
        update source_fact_publication_states
           set full_publication_requested = true
         where config_id = ? and source_instance = ? and fact_type = 'ISSUE'
        """,
        configId,
        SOURCE);

    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, fixture.runId(), PRE_TABLES);

    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.reason()).contains("全量事实重建尚未结算");
  }

  @Test
  void missingPublicationStateBlocksWriteback() {
    ReadyFixture fixture = readyFixture();
    jdbcTemplate.update("delete from source_fact_publication_states where config_id = ?", configId);
    var readiness = readinessService.assess(config, fixture.runId(), PRE_TABLES);
    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.reason()).contains("来源尚无 READY 发布状态");
  }

  @Test
  void nonReadyPublicationStateBlocksWriteback() {
    ReadyFixture fixture = readyFixture();
    jdbcTemplate.update("update source_fact_publication_states set readiness_status = 'BLOCKED' where config_id = ?", configId);
    var readiness = readinessService.assess(config, fixture.runId(), PRE_TABLES);
    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.reason()).contains("事实来源依赖未就绪");
  }

  @Test
  void unpublishedChangeHeadBlocksWriteback() {
    ReadyFixture fixture = readyFixture();
    jdbcTemplate.update(
        """
        update fact_change_heads
           set latest_change_version = 6
         where source_instance = ? and fact_type = 'ISSUE' and root_id = 1001
        """,
        SOURCE);

    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, fixture.runId(), PRE_TABLES);

    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.reason()).contains("待发布");
  }

  @Test
  void activeRunRewritingRelevantInputBlocksWriteback() {
    ReadyFixture fixture = readyFixture();
    long activeRun = insertRun("RUNNING", "{\"sourceTables\":[\"notes\"]}");
    insertTask(activeRun, "notes", "RUNNING");

    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, fixture.runId(), PRE_TABLES);

    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.reason()).contains("正在改写延期相关输入");
  }

  @Test
  void activeRunWithoutRelevantInputDoesNotBlockWriteback() {
    ReadyFixture fixture = readyFixture();
    insertRun("RUNNING", "{\"sourceTables\":[\"merge_requests\"]}");

    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, fixture.runId(), PRE_TABLES);

    assertThat(readiness.ready()).isTrue();
  }

  @Test
  void missingLabelEventVerificationBlocksWriteback() {
    ReadyFixture fixture = readyFixture();
    jdbcTemplate.update(
        """
        update sync_run_table_states
           set last_full_verified_at = null, dirty_flag = true
         where config_id = ? and source_instance = ? and source_table = 'resource_label_events'
        """,
        configId,
        SOURCE);

    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, fixture.runId(), PRE_TABLES);

    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.reason()).contains("标签事件历史");
  }

  private ReadyFixture readyFixture() {
    long run = insertRun("SUCCESS", "{\"sourceTables\":[\"issues\",\"notes\",\"label_links\",\"labels\"]}");
    successTasks(run, PRE_TABLES);
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            config_id, source_instance, fact_type, latest_mirror_run_id,
            readiness_status, full_publication_requested, updated_at)
        values (?, ?, 'ISSUE', ?, 'READY', false, current_timestamp)
        """,
        configId,
        SOURCE,
        run);
    for (String table :
        com.data.collection.platform.service.GitlabFactDependencyCatalog.require(
                com.data.collection.platform.entity.FactType.ISSUE)
            .requiredTables(config)) {
      jdbcTemplate.update(
          """
          insert into source_fact_dependency_states(
              config_id, source_instance, fact_type, source_table,
              latest_mirror_run_id, readiness_status, updated_at)
          values (?, ?, 'ISSUE', ?, ?, 'READY', current_timestamp)
          """,
          configId,
          SOURCE,
          table,
          run);
    }
    jdbcTemplate.update(
        """
        insert into sync_run_table_states(
            config_id, source_instance, source_table, mirror_table, primary_key_columns,
            sync_enabled, dirty_flag, last_full_verified_at, updated_at)
        values (?, ?, 'resource_label_events', 'ods_gitlab_resource_label_events', 'id',
                true, false, current_timestamp, current_timestamp)
        """,
        configId,
        SOURCE);
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version, updated_at)
        values (?, 'ISSUE', 1001, 5, 5, current_timestamp)
        """,
        SOURCE);
    return new ReadyFixture(run);
  }

  private long insertRun(String status, String payloadJson) {
    return jdbcTemplate.queryForObject(
        """
        insert into sync_runs(
            run_id, config_id, source_instance, run_type, trigger_type, status,
            exclusive_scope, payload_json, created_at, updated_at)
        values (?, ?, ?, 'TABLE_REFRESH', 'MANUAL', ?, 'delay-readiness-it', ?,
                current_timestamp, current_timestamp)
        returning id
        """,
        Long.class,
        "dr-" + System.nanoTime(),
        configId,
        SOURCE,
        status,
        payloadJson);
  }

  private void successTasks(long runId, List<String> tables) {
    tables.forEach(table -> insertTask(runId, table, "SUCCESS"));
  }

  private void insertTask(long runId, String table, String status) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, created_at, updated_at)
        values (?, ?, ?, ?, ?, 'TABLE', ?, 'INCREMENTAL', current_timestamp, current_timestamp)
        """,
        runId,
        configId,
        SOURCE,
        table,
        "ods_gitlab_" + table,
        status);
  }

  private void cleanUp() {
    jdbcTemplate.update(
        "delete from fact_change_heads where source_instance = ?", SOURCE);
    jdbcTemplate.update(
        "delete from customer_issue_delay_closure_requests where source_instance = ?", SOURCE);
    jdbcTemplate.update(
        "delete from gitlab_sync_configs where source_instance = ?", SOURCE);
  }

  private record ReadyFixture(long runId) {}
}
