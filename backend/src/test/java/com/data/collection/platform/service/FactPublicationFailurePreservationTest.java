package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationState;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 清理属于失败恢复；其异常不得替换需要管理员诊断的首个构建或发布异常。 */
class FactPublicationFailurePreservationTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final FactPublicationTransaction transactions = mock(FactPublicationTransaction.class);
  private final FactTaskExecutionGuard guard = mock(FactTaskExecutionGuard.class);
  private final ReadPublicationService lifecycle = mock(ReadPublicationService.class);
  private final ReadPublicationRepository repository = mock(ReadPublicationRepository.class);
  private final UUID publicationId = UUID.randomUUID();
  private final ReadPublication candidate = new ReadPublication(publicationId, UUID.randomUUID(),
      UUID.randomUUID(), 0, null, "{}", 0, null, ReadPublicationState.PROJECTING,
      "517", 100L, "owner", Instant.EPOCH, null);
  private FactTargetPublicationService service;

  @BeforeEach
  void setUp() {
    service = new FactTargetPublicationService(jdbc, null, null, null, null, transactions,
        guard, null, null, new GitlabMirrorProperties(), lifecycle, repository, null, null);
  }

  @Test
  void test_publication_and_abort_failures_preserve_publication_failure() {
    RuntimeException original = new IllegalStateException("投影产物校验失败");
    RuntimeException cleanup = new QueryTimeoutException("候选删除超时");
    when(repository.findPublication(publicationId)).thenReturn(Optional.of(candidate));
    when(transactions.commitCandidate(any())).thenThrow(original);
    when(lifecycle.abort(eq(publicationId), anyString())).thenThrow(cleanup);

    assertThatThrownBy(() -> service.publishPreparedCandidate(publicationId, "lease"))
        .isSameAs(original).satisfies(error ->
            org.assertj.core.api.Assertions.assertThat(error.getSuppressed()).containsExactly(cleanup));
  }

  @Test
  void test_successful_abort_keeps_original_publication_failure() {
    RuntimeException original = new IllegalStateException("发布事务失败");
    when(repository.findPublication(publicationId)).thenReturn(Optional.of(candidate));
    when(transactions.commitCandidate(any())).thenThrow(original);

    assertThatThrownBy(() -> service.publishPreparedCandidate(publicationId, "lease"))
        .isSameAs(original).satisfies(error ->
            org.assertj.core.api.Assertions.assertThat(error.getSuppressed()).isEmpty());
  }

  @Test
  void test_lost_parent_lease_does_not_abort_candidate() {
    RuntimeException original = new FactTaskLeaseLostException(100L, "父运行令牌已转移");
    when(repository.findPublication(publicationId)).thenReturn(Optional.of(candidate));
    when(transactions.commitCandidate(any())).thenThrow(original);
    when(lifecycle.abort(eq(publicationId), anyString())).thenThrow(new QueryTimeoutException("不应调用"));

    assertThatThrownBy(() -> service.publishPreparedCandidate(publicationId, "old-lease"))
        .isSameAs(original).satisfies(error ->
            org.assertj.core.api.Assertions.assertThat(error.getSuppressed()).isEmpty());
  }

  @Test
  void test_full_build_and_abort_failures_preserve_build_failure() {
    QueuedFactBuildTask task = task(true);
    RuntimeException original = new IllegalArgumentException("来源输入发生变化");
    RuntimeException cleanup = new QueryTimeoutException("中止事务超时");
    when(transactions.execute(any())).thenReturn(candidate);
    when(lifecycle.abortOwnedTaskCandidate(eq(publicationId), eq(100L), eq("owner"),
        eq("517"), eq("lease"), anyString())).thenThrow(cleanup);

    assertThatThrownBy(() -> service.publishFull(task, (target, progress) -> { throw original; }))
        .isSameAs(original).satisfies(error ->
            org.assertj.core.api.Assertions.assertThat(error.getSuppressed()).containsExactly(cleanup));
  }

  @Test
  void test_targeted_build_and_abort_failures_preserve_build_failure() {
    QueuedFactBuildTask task = task(false);
    RuntimeException original = new IllegalArgumentException("定向构建失败");
    RuntimeException cleanup = new QueryTimeoutException("中止事务超时");
    when(transactions.execute(any())).thenReturn(candidate).thenThrow(original);
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(100L), eq("owner")))
        .thenReturn(List.of(publicationId));
    when(lifecycle.abortOwnedTaskCandidate(eq(publicationId), eq(100L), eq("owner"),
        eq("517"), eq("lease"), anyString())).thenThrow(cleanup);

    assertThatThrownBy(() -> service.publish(task, (target, roots) -> null))
        .isSameAs(original).satisfies(error ->
            org.assertj.core.api.Assertions.assertThat(error.getSuppressed()).containsExactly(cleanup));
  }

  private QueuedFactBuildTask task(boolean full) {
    QueuedFactBuildTask task = mock(QueuedFactBuildTask.class);
    when(task.id()).thenReturn(100L);
    when(task.leaseOwner()).thenReturn("owner");
    when(task.factType()).thenReturn("ISSUE");
    when(task.sourceInstance()).thenReturn("default");
    when(task.factRunId()).thenReturn(517L);
    when(task.factRunLeaseToken()).thenReturn("lease");
    when(task.full()).thenReturn(full);
    return task;
  }
}
