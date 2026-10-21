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
  @org.junit.jupiter.api.BeforeEach
  void bindReadDependencyFixtures() {
    // 公式单测复用同一数据替身；实际修订隔离由真库读取测试覆盖。
    org.mockito.Mockito.lenient().when(queryService.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(queryService);
    org.mockito.Mockito.lenient().when(filterOptionService.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(filterOptionService);
    org.mockito.Mockito.lenient().when(persistenceSupport.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(persistenceSupport);
  }

  @Mock private ReviewDataRecordQueryService queryService;
  @Mock private ReviewDataRecordCommandService commandService;
  @Mock private ReviewDataFilterOptionService filterOptionService;
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;
  @Mock private PageRecordSnapshotService pageRecordSnapshotService;

  @Test
  void shouldInvalidateSnapshotsCreatedBeforeUnifiedReviewReadSource() {
    ReviewDataRecordListResponse response = mock(ReviewDataRecordListResponse.class);

    when(pageRecordSnapshotService.readOrRefresh(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class),
            any(), eq(ReviewDataRecordListResponse.class), any()))
        .thenReturn(response);
    ReviewDataRecordService service =
        new ReviewDataRecordService(
            queryService,
            commandService,
            filterOptionService,
            persistenceSupport,
            pageRecordSnapshotService).forRead(com.data.collection.platform.service.read.TestReadSelections.datasets());

    service.listRecords(
        new ReviewDataRecordQueryRequest(
            null, null, null, null, null, null, null, null, null, null,
            1, 20, "updatedAt", "desc"));

    ArgumentCaptor<PageRecordSnapshotService.SnapshotRequest> requestCaptor =
        ArgumentCaptor.forClass(PageRecordSnapshotService.SnapshotRequest.class);
    verify(pageRecordSnapshotService)        .readOrRefresh(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class),
            requestCaptor.capture(), eq(ReviewDataRecordListResponse.class), any());
    assertThat(requestCaptor.getValue().ruleVersion())
        .isEqualTo("review-data-records@2026-09-18-v7");
    verify(pageRecordSnapshotService).readOrRefresh(eq(com.data.collection.platform.service.read.TestReadSelections.datasets()),
        any(), eq(ReviewDataRecordListResponse.class), any());
  }
}
