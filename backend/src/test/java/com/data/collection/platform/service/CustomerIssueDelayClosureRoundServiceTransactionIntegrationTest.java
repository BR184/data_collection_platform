package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 延期重算与授权的事务边界（真实 PostgreSQL + Spring 事务代理）。
 *
 * <p>重算完成后若进度所有权丢失或相关事实版本漂移，本轮已经写入的事实与 generation 必须随
 * 授权失败整体回滚，不能以普通返回值结束并提交。
 */
@SpringBootTest(
    properties = "platform.gitlab-mirror.scheduler-enabled=false")
class CustomerIssueDelayClosureRoundServiceTransactionIntegrationTest {
  private static final String SOURCE = "default";
  private static final long PROJECT_ID = 325L;
  private static final long ISSUE_ID = 982001L;
  private static final long ISSUE_IID = 83001L;
  private static final long EXPECTED_VERSION = 42L;
  private static final String SCOPE_KEY = "round-rollback-test";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueDelayClosureRoundService roundService;
  @Autowired private CustomerIssueDelayClosureProgressRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private FactBuildService factBuildService;
  @MockitoBean private CustomerIssueDelaySourceReadinessService readinessService;

  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    cleanUp();
    long configId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(
                name, source_instance, source_enabled, auto_sync_enabled, db_name,
                db_username, db_password, delay_label_writeback_enabled,
                web_base_url, api_token)
            values ('delay-round-tx-it', ?, true, false, 'mirror', 'reader', 'secret',
                    true, 'http://127.0.0.1', 'token')
            returning id
            """,
            Long.class,
            SOURCE);
    config = new GitlabSyncConfig();
    config.setId(configId);
    config.setSourceInstance(SOURCE);
    config.setSourceEnabled(true);
    config.setDelayLabelWritebackEnabled(true);
    config.setWebBaseUrl("http://127.0.0.1");
    config.setApiToken("token");
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_system, source_instance, project_id, issue_id, issue_iid, title,
            issue_state, created_at_source, label_names, is_response_delayed,
            is_resolve_delayed, response_overdue, deleted)
        values ('GITLAB', ?, ?, ?, ?, 'issue', 'opened', '2026-05-01', 'before', false,
                false, false, false)
        """,
        SOURCE,
        PROJECT_ID,
        ISSUE_ID,
        ISSUE_IID);
    when(readinessService.assess(any(), any(), anyList()))
        .thenReturn(new CustomerIssueDelaySourceReadinessService.Readiness(
            true, null, EXPECTED_VERSION, 0L));
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void ownershipLostDuringRecalculationMustRollBackFactAndGenerationWrites() {
    CustomerIssueDelayClosureProgressClaim claim = startWaitingClaim();
    when(readinessService.currentFactVersion(SOURCE)).thenReturn(EXPECTED_VERSION);
    doAnswer(
            invocation -> {
              writeRecalculatedBusinessRows();
              takeOverInNewTransaction(claim.progress().id());
              return null;
            })
        .when(factBuildService)
        .refreshCustomerIssueDelayFactsForConfig(any(GitlabSyncConfig.class));

    assertThatThrownBy(() -> roundService.authorizeEnqueue(claim, config, EXPECTED_VERSION))
        .isInstanceOf(IllegalStateException.class);

    assertFactAndGenerationRolledBack();
    CustomerIssueDelayClosureProgress progress = repository.findBySource(SOURCE).orElseThrow();
    assertThat(progress.leaseOwner()).as("接管者保持所有权").isEqualTo("owner-b");
    assertThat(progress.stage()).isEqualTo(CustomerIssueDelayClosureStage.WAIT_FACT);
    assertThat(progress.authorizationRevision()).isNull();
    assertThat(progress.authorizedFactVersion()).isNull();
    assertThat(progress.authorized()).isFalse();
  }

  @Test
  void factVersionDriftDuringRecalculationMustRollBackFactAndGenerationWrites() {
    CustomerIssueDelayClosureProgressClaim claim = startWaitingClaim();
    when(readinessService.currentFactVersion(SOURCE)).thenReturn(EXPECTED_VERSION + 1);
    doAnswer(
            invocation -> {
              writeRecalculatedBusinessRows();
              return null;
            })
        .when(factBuildService)
        .refreshCustomerIssueDelayFactsForConfig(any(GitlabSyncConfig.class));

    assertThatThrownBy(() -> roundService.authorizeEnqueue(claim, config, EXPECTED_VERSION))
        .isInstanceOf(IllegalStateException.class);

    assertFactAndGenerationRolledBack();
    CustomerIssueDelayClosureProgress progress = repository.findBySource(SOURCE).orElseThrow();
    assertThat(progress.leaseOwner()).isEqualTo("owner-a");
    assertThat(progress.stage()).isEqualTo(CustomerIssueDelayClosureStage.WAIT_FACT);
    assertThat(progress.authorizationRevision()).isNull();
    assertThat(progress.authorized()).isFalse();
  }

  private CustomerIssueDelayClosureProgressClaim startWaitingClaim() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim claim =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    repository.bindMirror(claim, 77L).orElseThrow();
    repository.moveToFactWait(claim, "wait").orElseThrow();
    return claim;
  }

  private void writeRecalculatedBusinessRows() {
    jdbcTemplate.update(
        """
        update issue_fact
           set label_names = 'changed', is_response_delayed = true, is_resolve_delayed = true,
               response_overdue = true
         where issue_id = ?
        """,
        ISSUE_ID);
    jdbcTemplate.update(
        """
        insert into fact_projection_generations(
            source_instance, fact_type, scope_type, scope_key, generation, updated_at)
        values (?, 'ISSUE', 'PROJECT', ?, 1, clock_timestamp())
        on conflict (source_instance, fact_type, scope_type, scope_key) do update
          set generation = fact_projection_generations.generation + 1,
              updated_at = clock_timestamp()
        """,
        SOURCE,
        SCOPE_KEY);
  }

  private void takeOverInNewTransaction(long progressId) {
    TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    requiresNew.executeWithoutResult(
        status -> {
          jdbcTemplate.update(
              """
              update customer_issue_delay_closure_requests
                 set lease_until = clock_timestamp() - interval '1 second'
               where id = ?
              """,
              progressId);
          CustomerIssueDelayClosureProgressClaim taken =
              repository.claimBySource(SOURCE, "owner-b", "token-b", 60).orElseThrow();
          assertThat(taken.owner()).isEqualTo("owner-b");
        });
  }

  private void assertFactAndGenerationRolledBack() {
    var fact =
        jdbcTemplate.queryForMap(
            """
            select label_names, is_response_delayed, is_resolve_delayed, response_overdue
              from issue_fact where issue_id = ?
            """,
            ISSUE_ID);
    assertThat(fact.get("label_names"))
        .as("失去授权后重算的事实写入必须回滚")
        .isEqualTo("before");
    assertThat(fact.get("is_response_delayed")).isEqualTo(false);
    assertThat(fact.get("response_overdue")).isEqualTo(false);
    assertThat(fact.get("is_resolve_delayed")).isEqualTo(false);
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from fact_projection_generations
                 where source_instance = ? and scope_key = ?
                """,
                Long.class,
                SOURCE,
                SCOPE_KEY))
        .as("失去授权后推进的 generation 必须回滚")
        .isZero();
  }

  private void cleanUp() {
    jdbcTemplate.update(
        "delete from customer_issue_delay_closure_requests where source_instance = ?", SOURCE);
    jdbcTemplate.update(
        "delete from gitlab_sync_configs where source_instance = ?", SOURCE);
    jdbcTemplate.update(
        "delete from fact_projection_generations where source_instance = ? and scope_key = ?",
        SOURCE,
        SCOPE_KEY);
    jdbcTemplate.update(
        "delete from issue_fact where project_id = ? and issue_iid = ?",
        PROJECT_ID,
        ISSUE_IID);
  }
}
