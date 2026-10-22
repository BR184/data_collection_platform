package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 来源级持久进度的真实 PostgreSQL 事务与并发边界。
 *
 * <p>锁定：两推进者只有一个有效处理者、过期租约可接管且旧令牌不能推进、重启后从同一行恢复、
 * 处理中到达的更高请求不被本轮完成清除、配置保存可靠撤销授权且重开必须重新开始。
 */
@SpringBootTest
class CustomerIssueDelayClosureProgressRepositoryIntegrationTest {
  private static final String SOURCE = "default";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueDelayClosureProgressRepository repository;

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
            values ('delay-progress-it', ?, true, false, 'mirror', 'reader', 'secret',
                    true, 'http://127.0.0.1', 'token')
            returning id
            """,
            Long.class,
            SOURCE);
    config = config(true);
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void onlyOneOfTwoAdvancersOwnsTheSource() throws Exception {
    repository.requestRound(config, "test");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                start.await();
                return repository.claimBySource(SOURCE, "owner-a", "token-a", 60);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return repository.claimBySource(SOURCE, "owner-b", "token-b", 60);
              });
      start.countDown();
      Optional<CustomerIssueDelayClosureProgressClaim> a = first.get(30, TimeUnit.SECONDS);
      Optional<CustomerIssueDelayClosureProgressClaim> b = second.get(30, TimeUnit.SECONDS);

      assertThat(List.of(a.isPresent(), b.isPresent()).stream().filter(Boolean::booleanValue).count())
          .as("同一来源同一时刻只能有一个有效推进者")
          .isEqualTo(1L);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredLeaseCanBeTakenOverAndOldTokenCannotAdvance() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim first =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    jdbcTemplate.update(
        """
        update customer_issue_delay_closure_requests
           set lease_until = current_timestamp - interval '1 second'
         where id = ?
        """,
        first.progress().id());

    CustomerIssueDelayClosureProgressClaim second =
        repository.claimBySource(SOURCE, "owner-b", "token-b", 60).orElseThrow();

    assertThat(repository.bindMirror(first, 900L)).isEmpty();
    assertThat(repository.bindMirror(second, 901L)).isPresent();
    assertThat(repository.findBySource(SOURCE).orElseThrow().mirrorRunId()).isEqualTo(901L);
  }

  @Test
  void restartResumesTheSameWaitingProgress() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim claim =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    repository.bindMirror(claim, 77L).orElseThrow();
    repository.release(claim);

    CustomerIssueDelayClosureProgressRepository restarted =
        new CustomerIssueDelayClosureProgressRepository(jdbcTemplate);
    CustomerIssueDelayClosureProgress recovered =
        restarted.claimBySource(SOURCE, "owner-b", "token-b", 60).orElseThrow().progress();

    assertThat(recovered.stage()).isEqualTo(CustomerIssueDelayClosureStage.WAIT_MIRROR);
    assertThat(recovered.mirrorRunId()).isEqualTo(77L);
  }

  @Test
  void higherRequestArrivingDuringRoundIsPreservedAfterCompletion() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim claim =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    repository.moveToFactWait(claim, "wait").orElseThrow();
    repository.requestRound(config, "arrived-during-round");

    CustomerIssueDelayClosureProgress completed =
        repository.completeRound(claim).orElseThrow();

    assertThat(completed.completedRevision()).isEqualTo(claim.progress().processedRevision());
    assertThat(completed.requestRevision()).isGreaterThan(completed.completedRevision());
    assertThat(completed.stage())
        .as("处理中到达的更高请求必须保留到下一轮")
        .isEqualTo(CustomerIssueDelayClosureStage.REQUESTED);
  }

  @Test
  void configSaveClosesAuthorizationAndReopenRequiresNewRound() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim claim =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    repository.moveToFactWait(claim, "wait").orElseThrow();
    CustomerIssueDelayClosureProgress authorized =
        repository.authorizeEnqueue(claim, 42L).orElseThrow();
    assertThat(authorized.authorizedFactVersion()).isEqualTo(42L);

    repository.onConfigSaved(config(false));

    CustomerIssueDelayClosureProgress closed = repository.findBySource(SOURCE).orElseThrow();
    assertThat(closed.stage()).isEqualTo(CustomerIssueDelayClosureStage.IDLE);
    assertThat(closed.authorized()).isFalse();
    assertThat(repository.claimBySource(SOURCE, "owner-b", "token-b", 60)).isEmpty();

    repository.onConfigSaved(config(true));

    CustomerIssueDelayClosureProgress reopened = repository.findBySource(SOURCE).orElseThrow();
    assertThat(reopened.stage()).isEqualTo(CustomerIssueDelayClosureStage.REQUESTED);
    assertThat(reopened.authorized()).isFalse();
    assertThat(repository.claimBySource(SOURCE, "owner-b", "token-b", 60)).isPresent();
  }

  @Test
  void completedRoundKeepsAuthorizationForAlreadyEnqueuedJobs() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim claim =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    repository.moveToFactWait(claim, "wait").orElseThrow();
    repository.authorizeEnqueue(claim, 42L).orElseThrow();

    CustomerIssueDelayClosureProgress completed =
        repository.completeRound(claim).orElseThrow();

    assertThat(completed.stage()).isEqualTo(CustomerIssueDelayClosureStage.IDLE);
    assertThat(completed.authorizedFactVersion()).isEqualTo(42L);
    assertThat(completed.authorizationRevision())
        .isEqualTo(claim.progress().processedRevision());
  }

  private GitlabSyncConfig config(boolean writebackEnabled) {
    GitlabSyncConfig result = new GitlabSyncConfig();
    result.setId(configId);
    result.setSourceInstance(SOURCE);
    result.setSourceEnabled(true);
    result.setDelayLabelWritebackEnabled(writebackEnabled);
    result.setWebBaseUrl("http://127.0.0.1");
    result.setApiToken("token");
    return result;
  }

  private void cleanUp() {
    jdbcTemplate.update(
        "delete from customer_issue_delay_closure_requests where source_instance = ?", SOURCE);
    jdbcTemplate.update(
        "delete from gitlab_sync_configs where source_instance = ?", SOURCE);
  }
}
