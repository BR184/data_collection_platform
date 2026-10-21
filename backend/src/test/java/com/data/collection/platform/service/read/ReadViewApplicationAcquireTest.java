package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.controller.TestReadViews;
import com.data.collection.platform.entity.read.ReadView;
import com.data.collection.platform.service.BusinessDateProvider;
import com.data.collection.platform.service.PlatformPermissionService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 只对准备事务中的发布/回收竞态重新准备，首次无完整产出保持明确拒绝。 */
class ReadViewApplicationAcquireTest {
  private final ReadPageCatalog pages = mock(ReadPageCatalog.class);
  private final ReadViewPreparationService preparation = mock(ReadViewPreparationService.class);

  @Test
  void test_cross_page_fork_rechecks_target_permission_and_rejects_missing_parent_sources() {
    PublishedReadService reads = mock(PublishedReadService.class);
    PlatformPermissionService permissions = mock(PlatformPermissionService.class);
    ReadView parent = new ReadView(UUID.randomUUID(), "admin", "parent", "scope", java.time.LocalDate.of(2026,10,13),
        com.data.collection.platform.entity.read.ReadModelRules.VERSION, java.time.Instant.now().plusSeconds(300), null, Map.of());
    when(reads.loadOwnedView(any(),anyString(),anyString())).thenReturn(parent);
    when(pages.normalizeScope(any())).thenReturn(Map.of());
    when(pages.viewPermission("parent")).thenReturn("parent.view");
    when(pages.viewPermission("target")).thenReturn("target.view");
    ReadViewApplicationService application = new ReadViewApplicationService(pages, reads, mock(FactReadSelectionService.class),
        permissions, mock(BusinessDateProvider.class), new JsonUtils(new com.fasterxml.jackson.databind.ObjectMapper()),
        mock(ReadViewFreshnessService.class), preparation);
    org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("目标页权限撤销"))
        .when(permissions).requirePermission(any(),org.mockito.ArgumentMatchers.eq("target.view"));
    assertThatThrownBy(() -> application.fork(TestReadViews.authenticatedRequest(),parent.viewId(),"target",Map.of(),UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    org.mockito.Mockito.reset(permissions);
    when(reads.withinPublishedRead(org.mockito.ArgumentMatchers.eq(parent),any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      var reader = (java.util.function.Function<ReadView,Object>)invocation.getArgument(1);
      return reader.apply(parent);
    });
    when(pages.domainsForView(org.mockito.ArgumentMatchers.eq("target"),anyMap(),any())).thenReturn(java.util.Set.of(
        new com.data.collection.platform.entity.read.ReadPublicationDomainKey(
            com.data.collection.platform.entity.read.ReadPublicationProducerKind.GITLAB_FACT,"default","ISSUE","")));
    assertThatThrownBy(() -> application.fork(TestReadViews.authenticatedRequest(),parent.viewId(),"target",Map.of(),UUID.randomUUID()))
        .isInstanceOfSatisfying(ReadViewException.class,error -> assertThat(error.code()).isEqualTo(ReadViewException.Code.READ_VIEW_UNAVAILABLE));
    org.mockito.Mockito.verify(reads,org.mockito.Mockito.never()).forkView(any(),anyString(),anyString(),anyString(),anyString(),any());
  }

  private ReadViewApplicationService service() {
    when(pages.normalizeScope(any())).thenReturn(Map.of());
    return new ReadViewApplicationService(pages, mock(PublishedReadService.class), mock(FactReadSelectionService.class),
        mock(PlatformPermissionService.class), mock(BusinessDateProvider.class),
        new JsonUtils(new com.fasterxml.jackson.databind.ObjectMapper()), mock(ReadViewFreshnessService.class), preparation);
  }

  @Test
  void test_reclaimed_selection_reprepares_and_returns_complete_view() {
    ReadView complete = mock(ReadView.class);
    when(preparation.prepare(anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
        .thenThrow(ReadViewException.rebindRequired(null, "选中版本已回收")).thenReturn(complete);
    assertThat(service().acquire(TestReadViews.authenticatedRequest(), "page", Map.of(), UUID.randomUUID()))
        .isSameAs(complete);
  }

  @Test
  void test_no_complete_production_does_not_retry_or_certify_a_view() {
    AtomicInteger attempts = new AtomicInteger();
    when(preparation.prepare(anyString(), anyString(), anyString(), anyString(), anyMap(), any())).thenAnswer(call -> {
      attempts.incrementAndGet();
      throw ReadViewException.unavailable("尚无完整产出");
    });
    assertThatThrownBy(() -> service().acquire(TestReadViews.authenticatedRequest(), "page", Map.of(), UUID.randomUUID()))
        .isInstanceOfSatisfying(ReadViewException.class, error ->
            assertThat(error.code()).isEqualTo(ReadViewException.Code.READ_VIEW_UNAVAILABLE));
    assertThat(attempts.get()).as("首次无完整产出不属于瞬时竞态").isOne();
  }

  @Test
  void test_persistent_reclaim_race_is_bounded_and_preserves_structured_signal() {
    AtomicInteger attempts = new AtomicInteger();
    when(preparation.prepare(anyString(), anyString(), anyString(), anyString(), anyMap(), any())).thenAnswer(call -> {
      attempts.incrementAndGet();
      throw ReadViewException.rebindRequired(null, "回收竞态");
    });
    assertThatThrownBy(() -> service().acquire(TestReadViews.authenticatedRequest(), "page", Map.of(), UUID.randomUUID()))
        .isInstanceOfSatisfying(ReadViewException.class, error ->
            assertThat(error.code()).isEqualTo(ReadViewException.Code.READ_VIEW_REBIND_REQUIRED));
    assertThat(attempts.get()).as("准备竞态不能无限重试阻塞 HTTP").isEqualTo(3);
  }
}
