package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    when(persistenceSupport.listProblemItems(7L)).thenReturn(List.of(pendingPlaceholderItem()));

    Long itemId = service.createProblemItem(7L, request);

    assertThat(itemId).isEqualTo(11L);
    verify(persistenceSupport).assertRecordExists(7L);
    verify(persistenceSupport)
        .updateProblemItem(
            7L, 11L, "专家A", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交");
    verify(persistenceSupport, never())
        .insertProblemItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(persistenceSupport).touchRecord(7L);
  }

  @Test
  void shouldInsertProblemItemWhenNoPendingItemMatchesReviewer() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家B", "新提交");
    when(persistenceSupport.listProblemItems(7L)).thenReturn(List.of());
    when(persistenceSupport.insertProblemItem(
            7L, "专家B", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交", null))
        .thenReturn(12L);

    Long itemId = service.createProblemItem(7L, request);

    assertThat(itemId).isEqualTo(12L);
    verify(persistenceSupport).assertRecordExists(7L);
    verify(persistenceSupport)
        .insertProblemItem(
            7L, "专家B", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交", null);
    verify(persistenceSupport, never())
        .updateProblemItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(persistenceSupport).touchRecord(7L);
  }

  @Test
  void shouldInsertNewItemWhenReviewerHasCompletedProblemItem() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家A", "新提交");
    when(persistenceSupport.listProblemItems(7L))
        .thenReturn(
            List.of(
                new ReviewDataProblemItemResponse(
                    21L,
                    7L,
                    "专家A",
                    1.5,
                    "走查",
                    "3.2",
                    "完整性",
                    "问题描述B",
                    "建议方案B",
                    "负责人A",
                    "",
                    "新提交",
                    LocalDateTime.of(2026, 6, 5, 11, 0),
                    null)));
    when(persistenceSupport.insertProblemItem(
            7L, "专家A", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交", null))
        .thenReturn(13L);

    Long itemId = service.createProblemItem(7L, request);

    assertThat(itemId).isEqualTo(13L);
    verify(persistenceSupport)
        .insertProblemItem(
            7L, "专家A", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交", null);
    verify(persistenceSupport, never())
        .updateProblemItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(persistenceSupport).touchRecord(7L);
  }

  @Test
  void shouldInsertNewItemWhenReviewerHasDirtyPendingItemWithContent() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家A", "新提交");
    when(persistenceSupport.listProblemItems(7L))
        .thenReturn(
            List.of(
                new ReviewDataProblemItemResponse(
                    22L,
                    7L,
                    "专家A",
                    1.5,
                    "独立评审",
                    "2.1",
                    "完整性",
                    "缺少异常流程",
                    "补充异常流程",
                    "负责人A",
                    "",
                    "未评审",
                    LocalDateTime.of(2026, 6, 5, 12, 0),
                    null)));
    when(persistenceSupport.insertProblemItem(
            7L, "专家A", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交", null))
        .thenReturn(14L);

    Long itemId = service.createProblemItem(7L, request);

    assertThat(itemId).isEqualTo(14L);
    verify(persistenceSupport)
        .insertProblemItem(
            7L, "专家A", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交", null);
    verify(persistenceSupport, never())
        .updateProblemItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(persistenceSupport).touchRecord(7L);
  }

  @Test
  void shouldRejectBlankProblemStatusWhenCreatingProblemItem() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest nullStatus = problemRequest("专家A", null);
    ReviewDataProblemItemSaveRequest blankStatus = problemRequest("专家A", "  ");

    assertThatThrownBy(() -> service.createProblemItem(7L, nullStatus))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("必须选择问题状态");
    assertThatThrownBy(() -> service.createProblemItem(7L, blankStatus))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("必须选择问题状态");

    verify(persistenceSupport, times(2)).assertRecordExists(7L);
    verifyNoMoreInteractions(persistenceSupport);
  }

  @Test
  void shouldRejectManualPendingStatusWhenCreatingProblemItem() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家A", "未评审");

    assertThatThrownBy(() -> service.createProblemItem(7L, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("未评审为系统默认状态，不能手动选择");

    verify(persistenceSupport).assertRecordExists(7L);
    verifyNoMoreInteractions(persistenceSupport);
  }

  @Test
  void shouldMaterializeHistoricalRecordBeforeFirstProblemWrite() {
    ReviewDataRecordCommandService service = service();
    ReviewDataProblemItemSaveRequest request = problemRequest("专家B", "新提交");
    when(matchModeMaterializeService.materializeForMutation(-7L)).thenReturn(7L);
    when(persistenceSupport.listProblemItems(7L)).thenReturn(List.of());
    when(persistenceSupport.insertProblemItem(
            7L, "专家B", 1.5, "独立评审", "2.1", "完整性", "缺少异常流程", "补充异常流程", "负责人A", "", "新提交", null))
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

  private ReviewDataProblemItemResponse pendingPlaceholderItem() {
    return new ReviewDataProblemItemResponse(
        11L,
        7L,
        "专家A",
        0D,
        "独立评审",
        "",
        "无问题",
        "待评审",
        "",
        "",
        "",
        "未评审",
        LocalDateTime.of(2026, 6, 5, 10, 0),
        null);
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
