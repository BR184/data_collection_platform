package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataRecordServiceTest {
  @Mock private ReviewDataRecordQueryService queryService;
  @Mock private ReviewDataRecordCommandService commandService;
  @Mock private ReviewDataFilterOptionService filterOptionService;
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;
  @Mock private PageRecordSnapshotService pageRecordSnapshotService;

  @Test
  void shouldInvalidateSnapshotsCreatedBeforeUnifiedReviewReadSource() {
    ReviewDataRecordListResponse response = mock(ReviewDataRecordListResponse.class);
    when(pageRecordSnapshotService.reviewDataSourceVersion()).thenReturn("review-source");
    when(pageRecordSnapshotService.readOrRefresh(
            any(), eq(ReviewDataRecordListResponse.class), any()))
        .thenReturn(response);
    ReviewDataRecordService service =
        new ReviewDataRecordService(
            queryService,
            commandService,
            filterOptionService,
            persistenceSupport,
            pageRecordSnapshotService);

    service.listRecords(
        new ReviewDataRecordQueryRequest(
            null, null, null, null, null, null, null, null, null, null,
            1, 20, "updatedAt", "desc"));

    ArgumentCaptor<PageRecordSnapshotService.SnapshotRequest> requestCaptor =
        ArgumentCaptor.forClass(PageRecordSnapshotService.SnapshotRequest.class);
    verify(pageRecordSnapshotService)
        .readOrRefresh(
            requestCaptor.capture(), eq(ReviewDataRecordListResponse.class), any());
    assertThat(requestCaptor.getValue().ruleVersion())
        .isEqualTo("review-data-records@2026-09-18-v7");
  }
}
