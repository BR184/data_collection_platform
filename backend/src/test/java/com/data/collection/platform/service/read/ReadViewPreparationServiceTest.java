package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadView;
import com.data.collection.platform.service.BusinessDateProvider;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 业务日跨零点也必须只捕获一次，闭包与视图身份不能使用不同的日期。 */
class ReadViewPreparationServiceTest {
  @Test
  void test_business_day_rollover_keeps_closure_and_identity_on_one_day() {
    BusinessDateProvider dates = mock(BusinessDateProvider.class);
    when(dates.businessDate()).thenReturn(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14));
    ReadPageCatalog pages = mock(ReadPageCatalog.class);
    when(pages.domains(eq("page"), anyMap(), any(LocalDate.class))).thenAnswer(call ->
        Set.of(ReadPublicationDomainKey.gitlabFact(call.<LocalDate>getArgument(2).toString(), FactType.ISSUE)));
    PublishedReadService reads = mock(PublishedReadService.class);
    when(reads.acquireView(any())).thenReturn(mock(ReadView.class));
    new ReadViewPreparationService(pages, reads, dates, mock(org.springframework.transaction.PlatformTransactionManager.class))
        .prepare("user", "session", "page", "scope", Map.of(), UUID.randomUUID());
    ArgumentCaptor<AcquireReadViewRequest> captured = ArgumentCaptor.forClass(AcquireReadViewRequest.class);
    verify(reads).acquireView(captured.capture());
    assertThat(captured.getValue().businessDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(captured.getValue().domains()).containsExactly(
        ReadPublicationDomainKey.gitlabFact("2026-10-13", FactType.ISSUE));
  }

  @Test
  void test_only_serialization_conflicts_restart_a_bounded_read_transaction() {
    var pages = mock(ReadPageCatalog.class);
    when(pages.domains(anyString(), anyMap(), any())).thenReturn(Set.of(ReadPublicationDomainKey.gitlabFact("default", FactType.ISSUE)));
    var reads = mock(PublishedReadService.class);
    var conflict = new org.springframework.dao.CannotAcquireLockException("concurrent publication",
        new java.sql.SQLException("snapshot moved", "40001"));
    var expected = mock(ReadView.class);
    when(reads.acquireView(any())).thenThrow(conflict).thenReturn(expected);
    var service = new ReadViewPreparationService(pages, reads, () -> LocalDate.of(2026, 10, 13),
        mock(org.springframework.transaction.PlatformTransactionManager.class));
    assertThat(service.prepare("user", "session", "page", "scope", Map.of(), UUID.randomUUID())).isSameAs(expected);
    org.mockito.Mockito.clearInvocations(reads);
    when(reads.acquireView(any())).thenThrow(conflict);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepare("user", "session", "page", "scope", Map.of(), UUID.randomUUID()))
        .isSameAs(conflict);
    org.mockito.Mockito.verify(reads, org.mockito.Mockito.times(3)).acquireView(any());
    var integrity = new org.springframework.dao.DataIntegrityViolationException("bad relationship",
        new java.sql.SQLException("invalid input", "23503"));
    org.mockito.Mockito.doThrow(integrity).when(reads).acquireView(any());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepare("user", "session", "page", "scope", Map.of(), UUID.randomUUID()))
        .isSameAs(integrity);
  }
}
