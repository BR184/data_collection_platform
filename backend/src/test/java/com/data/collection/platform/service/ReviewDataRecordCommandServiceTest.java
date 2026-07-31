package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataRecordCommandServiceTest {
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;
  @Mock private ReviewDataMatchModeMaterializeService matchModeMaterializeService;

  @Test
  void shouldUpdatePendingProblemItemForSameReviewerWhenCreatingProblemItem() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家A", "新提交");
    when(persistenceSupport.listProblemItems(7L))
        .thenReturn(
            List.of(
                new ReviewDataProblemItemResponse(
                    11L,
                    7L,
                    "专家A",
                    0D,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "未评审",
                    LocalDateTime.of(2026, 6, 5, 10, 0),
                    null)));

    Long itemId = service.createProblemItem(7L, request);

    assertThat(itemId).isEqualTo(11L);
    verify(persistenceSupport).assertRecordExists(7L);
    verify(persistenceSupport)
        .updateProblemItem(
            7L,
            11L,
            "专家A",
            1.5,
            "独立评审",
            "2.1",
            "完整性",
            "缺少异常流程",
            "补充异常流程",
            "负责人A",
            "",
            "新提交");
    verify(persistenceSupport, never())
        .insertProblemItem(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(persistenceSupport).touchRecord(7L);
  }

  @Test
  void shouldInsertProblemItemWhenNoPendingItemMatchesReviewer() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家B", "新提交");
    when(persistenceSupport.listProblemItems(7L)).thenReturn(List.of());
    when(persistenceSupport.insertProblemItem(
            7L,
            "专家B",
            1.5,
            "独立评审",
            "2.1",
            "完整性",
            "缺少异常流程",
            "补充异常流程",
            "负责人A",
            "",
            "新提交",
            null))
        .thenReturn(12L);

    Long itemId = service.createProblemItem(7L, request);

    assertThat(itemId).isEqualTo(12L);
    verify(persistenceSupport).assertRecordExists(7L);
    verify(persistenceSupport)
        .insertProblemItem(
            7L,
            "专家B",
            1.5,
            "独立评审",
            "2.1",
            "完整性",
            "缺少异常流程",
            "补充异常流程",
            "负责人A",
            "",
            "新提交",
            null);
    verify(persistenceSupport, never())
        .updateProblemItem(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(persistenceSupport).touchRecord(7L);
  }

  @Test
  void shouldMaterializeHistoricalRecordBeforeFirstProblemWrite() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家B", "新提交");
    when(matchModeMaterializeService.materializeForMutation(-7L)).thenReturn(7L);
    when(persistenceSupport.listProblemItems(7L)).thenReturn(List.of());
    when(persistenceSupport.insertProblemItem(
            7L,
            "专家B",
            1.5,
            "独立评审",
            "2.1",
            "完整性",
            "缺少异常流程",
            "补充异常流程",
            "负责人A",
            "",
            "新提交",
            null))
        .thenReturn(12L);

    Long itemId = service.createProblemItem(-7L, request);

    assertThat(itemId).isEqualTo(12L);
    verify(matchModeMaterializeService).materializeForMutation(-7L);
    verify(persistenceSupport).assertRecordExists(7L);
  }

  private ReviewDataRecordCommandService service() {
    return new ReviewDataRecordCommandService(
        persistenceSupport, matchModeMaterializeService);
  }

  private ReviewDataProblemItemSaveRequest problemRequest(String reviewerName, String problemStatus) {
    return new ReviewDataProblemItemSaveRequest(
        reviewerName,
        1.5,
        "独立评审",
        "2.1",
        "完整性",
        "缺少异常流程",
        "补充异常流程",
        "负责人A",
        "",
        problemStatus);
  }
}
