package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataMatchModeMaterializeServiceTest {
  @Mock private ReviewDataMatchModeRecordRepository repository;
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;

  @Test
  void existingMaterializedRecordMutationKeepsTheExistingPlatformOwnedAggregate() {
    ReviewDataMatchModeMaterializeService service = service();
    when(repository.findMaterializedRecordId(-7L)).thenReturn(88L);

    Long recordId = service.materializeForMutation(-7L);

    assertThat(recordId).isEqualTo(88L);
    verify(repository).findMaterializedRecordId(-7L);
    verifyNoInteractions(persistenceSupport);
  }

  private ReviewDataMatchModeMaterializeService service() {
    return new ReviewDataMatchModeMaterializeService(repository, persistenceSupport);
  }
}
