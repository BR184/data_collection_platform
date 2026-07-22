package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataMatchModeMaterializeServiceTest {
  @Mock private ReviewDataMatchModeRecordRepository repository;
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;

  @Test
  void platformOwnedRecordHandoverSkipsAllFormalWrites() {
    ReviewDataMatchModeMaterializeService service = service();
    ReviewDataMatchModeRecordRepository.MatchModeRecordSource source = source();
    when(repository.findMaterializedRecordId(-7L)).thenReturn(88L);
    when(repository.findMaterializedAuthority(-7L))
        .thenReturn(ReviewDataMatchModeRecordRepository.RecordAuthority.PLATFORM_OWNED);

    ReviewDataMatchModeMaterializeService.MaterializeResult result =
        service.materializeForHandover(source);

    assertThat(result.recordId()).isEqualTo(88L);
    assertThat(result.outcome())
        .isEqualTo(ReviewDataMatchModeMaterializeService.MaterializeOutcome.SKIPPED_PLATFORM_OWNED);
    verifyNoInteractions(persistenceSupport);
    verify(repository, never()).linkMaterializedRecord(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void existingMaterializedRecordMutationClaimsOwnershipWithoutRefreshingLegacyData() {
    ReviewDataMatchModeMaterializeService service = service();
    when(repository.findMaterializedRecordId(-7L)).thenReturn(88L);

    Long recordId = service.materializeForMutation(-7L);

    assertThat(recordId).isEqualTo(88L);
    verify(repository).claimPlatformOwnership(88L);
    verifyNoInteractions(persistenceSupport);
  }

  private ReviewDataMatchModeMaterializeService service() {
    return new ReviewDataMatchModeMaterializeService(repository, persistenceSupport);
  }

  private ReviewDataMatchModeRecordRepository.MatchModeRecordSource source() {
    return new ReviewDataMatchModeRecordRepository.MatchModeRecordSource(
        new ReviewDataMatchModeRecordRepository.ReportRow(
            7L,
            "legacy-review-7",
            "CC2026R4",
            "需求说明书",
            "几何模块",
            "需求说明书评审",
            "需求说明书评审",
            "需求说明书评审",
            LocalDateTime.of(2026, 7, 1, 9, 0),
            "负责人",
            List.of("专家A"),
            10,
            1,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            1,
            0,
            0,
            0,
            "",
            List.of(),
            List.of(),
            LocalDateTime.of(2026, 7, 1, 8, 0)),
        List.of(),
        List.of(),
        List.of());
  }
}
