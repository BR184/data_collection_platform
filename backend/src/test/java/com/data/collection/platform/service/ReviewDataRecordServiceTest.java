package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataRecordServiceTest {
  @Mock private ReviewDataRecordQueryService queryService;
  @Mock private ReviewDataRecordCommandService commandService;
  @Mock private ReviewDataFilterOptionService filterOptionService;
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;

  @Test
  void shouldComposeCreateRecordCommandWithDetailQuery() {
    ReviewDataRecordService service =
        new ReviewDataRecordService(queryService, commandService, filterOptionService, persistenceSupport);
    ReviewDataRecordSaveRequest request = recordRequest();
    ReviewDataRecordDetailResponse detail = detail(10L);

    when(commandService.createRecord(request)).thenReturn(10L);
    when(queryService.getRecordDetail(10L)).thenReturn(detail);

    assertThat(service.createRecord(request)).isSameAs(detail);
    verify(commandService).createRecord(request);
    verify(queryService).getRecordDetail(10L);
  }

  @Test
  void shouldComposeCreateProblemItemCommandWithItemQuery() {
    ReviewDataRecordService service =
        new ReviewDataRecordService(queryService, commandService, filterOptionService, persistenceSupport);
    ReviewDataProblemItemSaveRequest request = problemRequest();
    ReviewDataProblemItemResponse item =
        new ReviewDataProblemItemResponse(
            20L,
            10L,
            "Expert A",
            1.5,
            "Walkthrough",
            "Section 1",
            "Completeness",
            "Missing field",
            "Add field",
            "Owner A",
            "",
            "Open",
            LocalDateTime.of(2026, 4, 27, 10, 0));

    when(commandService.createProblemItem(10L, request)).thenReturn(20L);
    when(queryService.getProblemItem(10L, 20L)).thenReturn(item);

    assertThat(service.createProblemItem(10L, request)).isSameAs(item);
    verify(commandService).createProblemItem(10L, request);
    verify(queryService).getProblemItem(10L, 20L);
  }

  private ReviewDataRecordSaveRequest recordRequest() {
    return new ReviewDataRecordSaveRequest(
        "Project A",
        "Review A",
        "Module A",
        "Design review",
        LocalDate.of(2026, 4, 27),
        "Owner A",
        List.of("Expert A"),
        12,
        "Spec",
        "Author A",
        "v1");
  }

  private ReviewDataProblemItemSaveRequest problemRequest() {
    return new ReviewDataProblemItemSaveRequest(
        "Expert A",
        1.5,
        "Walkthrough",
        "Section 1",
        "Completeness",
        "Missing field",
        "Add field",
        "Owner A",
        "",
        "Open");
  }

  private ReviewDataRecordDetailResponse detail(Long recordId) {
    return new ReviewDataRecordDetailResponse(
        new ReviewDataRecordRowResponse(
            recordId,
            "Project A",
            "Review A",
            "Module A",
            "Design review",
            LocalDate.of(2026, 4, 27),
            "Owner A",
            "Expert A",
            12,
            "Spec",
            "Author A",
            "v1",
            0,
            0D,
            LocalDateTime.of(2026, 4, 27, 10, 0),
            false),
        List.of("Expert A"),
        List.of());
  }
}
