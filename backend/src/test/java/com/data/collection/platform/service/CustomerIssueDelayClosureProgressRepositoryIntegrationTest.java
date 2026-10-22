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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 来源级持久进度的真实 PostgreSQL 事务与并发边界。
 *
 * <p>锁定：两推进者只有一个有效处理者、过期租约可接管且旧令牌不能推进、重启后从同一行恢复、
 * 等待或分页期间到达的更高请求不会被恢复领取吞掉、租约到期未接管或长事务内到期都不能推进/
 * 授权/结算、配置保存可靠撤销授权且重开必须重新开始。
 */
@SpringBootTest
class CustomerIssueDelayClosureProgressRepositoryIntegrationTest {
  private static final String SOURCE = "default";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueDelayClosureProgressRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

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

  @Test
  void reclaimWhileWaitingMustKeepOriginalRevisionAndLeaveNewRequestForNextRound() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim first =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    repository.bindMirror(first, 77L).orElseThrow();
    assertThat(first.progress().processedRevision()).isEqualTo(1L);
    assertThat(repository.release(first)).isTrue();

    repository.requestRound(config, "arrived-while-waiting");
    CustomerIssueDelayClosureProgressClaim second =
        repository.claimBySource(SOURCE, "owner-b", "token-b", 60).orElseThrow();

    assertThat(second.progress().processedRevision())
        .as("恢复旧轮必须保留开始新轮时固定的处理修订")
        .isEqualTo(1L);
    assertThat(second.progress().requestRevision()).isEqualTo(2L);
    assertThat(second.progress().stage()).isEqualTo(CustomerIssueDelayClosureStage.WAIT_MIRROR);
    assertThat(second.progress().mirrorRunId()).isEqualTo(77L);

    repository.moveToFactWait(second, "wait").orElseThrow();
    repository.authorizeEnqueue(second, 42L).orElseThrow();
    CustomerIssueDelayClosureProgress completed =
        repository.completeRound(second).orElseThrow();

    assertThat(completed.completedRevision()).isEqualTo(1L);
    assertThat(completed.requestRevision()).isEqualTo(2L);
    assertThat(completed.hasPendingRequest())
        .as("等待期间到达的请求 2 必须留给下一轮，不能被旧轮结算")
        .isTrue();
    assertThat(completed.stage()).isEqualTo(CustomerIssueDelayClosureStage.REQUESTED);
  }

  @Test
  void reclaimWhilePagingMustKeepCursorAndOriginalRevision() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim first =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    repository.moveToFactWait(first, "wait").orElseThrow();
    repository.authorizeEnqueue(first, 42L).orElseThrow();
    repository.advanceCursor(first, 5001L).orElseThrow();
    assertThat(repository.release(first)).isTrue();

    repository.requestRound(config, "arrived-while-paging");
    CustomerIssueDelayClosureProgressClaim second =
        repository.claimBySource(SOURCE, "owner-b", "token-b", 60).orElseThrow();

    assertThat(second.progress().processedRevision()).isEqualTo(1L);
    assertThat(second.progress().requestRevision()).isEqualTo(2L);
    assertThat(second.progress().enqueueCursorIssueId()).isEqualTo(5001L);
    assertThat(second.progress().stage()).isEqualTo(CustomerIssueDelayClosureStage.ENQUEUE);

    CustomerIssueDelayClosureProgress completed =
        repository.completeRound(second).orElseThrow();

    assertThat(completed.completedRevision()).isEqualTo(1L);
    assertThat(completed.stage()).isEqualTo(CustomerIssueDelayClosureStage.REQUESTED);
    assertThat(completed.hasPendingRequest()).isTrue();
  }

  @Test
  void expiredLeaseWithoutTakeoverMustNotAdvanceAuthorizeOrSettle() {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim claim =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 60).orElseThrow();
    jdbcTemplate.update(
        """
        update customer_issue_delay_closure_requests
           set lease_until = clock_timestamp() - interval '1 second'
         where id = ?
        """,
        claim.progress().id());

    assertThat(repository.bindMirror(claim, 900L)).isEmpty();
    assertThat(repository.moveToFactWait(claim, "late")).isEmpty();
    assertThat(repository.authorizeEnqueue(claim, 42L)).isEmpty();
    assertThat(repository.completeRound(claim)).isEmpty();
    assertThat(repository.release(claim)).isFalse();

    CustomerIssueDelayClosureProgress current = repository.findBySource(SOURCE).orElseThrow();
    assertThat(current.stage()).isEqualTo(CustomerIssueDelayClosureStage.REQUESTED);
    assertThat(current.mirrorRunId()).isNull();
    assertThat(current.authorized()).isFalse();
    assertThat(current.leaseOwner()).isEqualTo("owner-a");
  }

  @Test
  void expiryInsideLongTransactionMustInvalidateProgressMutations() throws Exception {
    repository.requestRound(config, "test");
    CustomerIssueDelayClosureProgressClaim claim =
        repository.claimBySource(SOURCE, "owner-a", "token-a", 1).orElseThrow();

    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          try {
            Thread.sleep(1500L);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
          assertThat(repository.moveToFactWait(claim, "late"))
              .as("事务开始后才到期也必须按真实时钟拒绝推进")
              .isEmpty();
          assertThat(repository.authorizeEnqueue(claim, 42L)).isEmpty();
          assertThat(repository.completeRound(claim)).isEmpty();
        });

    CustomerIssueDelayClosureProgress current = repository.findBySource(SOURCE).orElseThrow();
    assertThat(current.stage()).isEqualTo(CustomerIssueDelayClosureStage.REQUESTED);
    assertThat(current.authorized()).isFalse();
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
