package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomain;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationState;
import com.data.collection.platform.entity.read.ReadView;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 已经选中的完整版本在取得保护锁后可以建立视图；回收竞态要求重新准备。
 */
class PublishedReadServiceTest {
  private static final ReadPublicationDomainKey ISSUE_DOMAIN =
      ReadPublicationDomainKey.gitlabFact("default", FactType.ISSUE);

  private final ReadPublicationRepository publicationRepository = mock(ReadPublicationRepository.class);
  private final ReadViewRepository viewRepository = mock(ReadViewRepository.class);
  private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
  private final PublishedReadService service =
      new PublishedReadService(
          publicationRepository, viewRepository, new ReadFallbackProperties(), transactionManager);

  @Test
  void test_acquire_view_keeps_complete_publication_superseded_between_read_and_lock() {
    UUID domainId = UUID.randomUUID();
    ReadPublication v1 = publication(domainId, UUID.randomUUID(), 1L);
    when(publicationRepository.findDomain(ISSUE_DOMAIN))
        .thenReturn(Optional.of(new ReadPublicationDomain(domainId, ISSUE_DOMAIN, v1.id(), 2L)));
    when(publicationRepository.findCurrentPublication(domainId)).thenReturn(Optional.of(v1));
    when(publicationRepository.lockPublicationForShare(v1.id())).thenReturn(Optional.of(v1));
    // 该版本持锁且仍完整，后续指针推进不影响本次完整读取。
    var view = service.acquireView(request());
    assertThat(view.requireDomain(ISSUE_DOMAIN).publicationId()).isEqualTo(v1.id());
  }

  @Test
  void test_acquire_view_rejects_publication_reclaimed_before_lock() {
    UUID domainId = UUID.randomUUID();
    ReadPublication v1 = publication(domainId, UUID.randomUUID(), 1L);
    when(publicationRepository.findDomain(ISSUE_DOMAIN))
        .thenReturn(Optional.of(new ReadPublicationDomain(domainId, ISSUE_DOMAIN, v1.id(), 2L)));
    when(publicationRepository.findCurrentPublication(domainId)).thenReturn(Optional.of(v1));
    // 加锁时该版本已被回收（行已删除）：拒绝建立视图，调用方重新获取。
    when(publicationRepository.lockPublicationForShare(v1.id())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.acquireView(request()))
        .isInstanceOfSatisfying(
            ReadViewException.class,
            error ->
                assertThat(error.code())
                    .isEqualTo(ReadViewException.Code.READ_VIEW_REBIND_REQUIRED));
  }

  @Test
  void test_renew_view_skips_lease_write_while_remaining_exceeds_heartbeat() {
    Instant freshExpiry = Instant.now().plusSeconds(300);
    UUID viewId = renewFixture(freshExpiry);

    ReadView view = service.renewView(viewId, "tester", "session-1");

    assertThat(view.expiresAt()).isEqualTo(freshExpiry);
    // 每次读取都更新视图行会与并发读取的 KEY SHARE 冲突；租约充足时不得写库。
    verify(viewRepository, never()).extendLease(any(), any(), any());
  }

  @Test
  void test_renew_view_extends_lease_only_when_remaining_reaches_heartbeat() {
    Instant nearExpiry = Instant.now().plusSeconds(10);
    UUID viewId = renewFixture(nearExpiry);

    ReadView view = service.renewView(viewId, "tester", "session-1");

    assertThat(view.expiresAt()).isAfter(nearExpiry);
    verify(viewRepository, times(1)).extendLease(any(), any(), any());
  }

  private UUID renewFixture(Instant expiry) {
    UUID domainId = UUID.randomUUID();
    UUID publicationId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    ReadPublication published = publication(domainId, publicationId, 1L);
    when(viewRepository.findBindings(viewId)).thenReturn(java.util.List.of(
        new ReadViewRepository.ReadViewBinding(ISSUE_DOMAIN, domainId, publicationId, UUID.randomUUID(), 1L,
            Instant.now(), ReadPublicationState.PUBLISHED)));
    when(publicationRepository.lockPublicationForShare(publicationId)).thenReturn(Optional.of(published));
    when(publicationRepository.findCurrentPublication(domainId)).thenReturn(Optional.of(published));
    when(viewRepository.lockViewForUpdate(viewId)).thenReturn(Optional.of(new ReadViewRepository.ReadViewRow(
        viewId, "tester", "session-1", "customer-issue-records", "scope-1",
        java.time.LocalDate.of(2026, 10, 1), "rule-1", expiry, null)));
    return viewId;
  }

  private AcquireReadViewRequest request() {
    return new AcquireReadViewRequest(
        "tester", "session-1", "customer-issue-records", "scope-1", java.time.LocalDate.of(2026, 10, 1),
        "rule-1", Set.of(ISSUE_DOMAIN), java.util.UUID.randomUUID());
  }

  private ReadPublication publication(UUID domainId, UUID publicationId, long sequence) {
    Instant now = Instant.now();
    return new ReadPublication(
        publicationId, domainId, UUID.randomUUID(), sequence, null, "{}", 10L, null,
        ReadPublicationState.PUBLISHED, "run", 1L, "owner", now, now);
  }
}
