package com.data.collection.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import com.data.collection.platform.entity.ReviewDataSummaryResponse;
import com.data.collection.platform.common.exception.GlobalRestExceptionHandler;
import com.data.collection.platform.config.ReviewDataProperties;
import com.data.collection.platform.service.ReviewDataLegacyExcelImportService;
import com.data.collection.platform.service.ReviewDataExcelExportService;
import com.data.collection.platform.service.ReviewDataTemplateWorkbookService;
import com.data.collection.platform.service.ReviewDataRecordQueryRequest;
import com.data.collection.platform.service.ReviewDataRecordService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ReviewDataControllerTest {

  @Mock private ReviewDataRecordService reviewDataRecordService;
  @Mock private ReviewDataLegacyExcelImportService legacyExcelImportService;
  @Mock private ReviewDataExcelExportService excelExportService;
  @Mock private ReviewDataTemplateWorkbookService templateWorkbookService;

  private MockMvc mockMvc;
  private ReviewDataProperties reviewDataProperties;

  @BeforeEach
  void setUp() {
    reviewDataProperties = new ReviewDataProperties();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ReviewDataController(
                    reviewDataRecordService,
                    new ReviewDataRequestAssembler(),
                    legacyExcelImportService,
                    excelExportService,
                    templateWorkbookService,
                    reviewDataProperties))
            .setControllerAdvice(new GlobalRestExceptionHandler())
            .build();
  }

  @Test
  void shouldReturnReviewDataRecordList() throws Exception {
    when(reviewDataRecordService.listRecords(any(ReviewDataRecordQueryRequest.class)))
        .thenReturn(
            new ReviewDataRecordListResponse(
                List.of(
                    new ReviewDataRecordRowResponse(
                        1L,
                        "CrownCAD",
                        "Sketch Design Review",
                        "Sketch",
                        "Document Review",
                        LocalDate.of(2026, 4, 10),
                        "Alice",
                        "Bob, Carol",
                        24,
                        "Design Doc",
                        "Dora",
                        "V1.0",
                        5,
                        0.21,
                        LocalDateTime.of(2026, 4, 12, 10, 0),
                        false)),
                1,
                2,
                10,
                "updatedAt",
                "desc",
                new ReviewDataSummaryResponse(1, 5, 24, 5)));

    mockMvc.perform(
            get("/api/review-data/records")
                .param("keyword", "review")
                .param("title", "design")
                .param("projectName", "CrownCAD")
                .param("moduleName", "Sketch")
                .param("reviewOwner", "Alice")
                .param("reviewType", "Document Review")
                .param("problemStatus", "Resolved")
                .param("reviewExpert", "Bob")
                .param("filterGroup", "{\"logic\":\"AND\",\"conditions\":[]}")
                .param("sourceInstance", "cc")
                .param("page", "2")
                .param("size", "10")
                .param("sortBy", "updatedAt")
                .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.records[0].title").value("Sketch Design Review"))
        .andExpect(jsonPath("$.data.records[0].problemCount").value(5))
        .andExpect(jsonPath("$.data.summary.totalProblemItems").value(5));

    var requestCaptor = org.mockito.ArgumentCaptor.forClass(ReviewDataRecordQueryRequest.class);
    verify(reviewDataRecordService).listRecords(requestCaptor.capture());
    ReviewDataRecordQueryRequest request = requestCaptor.getValue();
    Assertions.assertEquals("review", request.keyword());
    Assertions.assertEquals("design", request.title());
    Assertions.assertEquals("CrownCAD", request.projectName());
    Assertions.assertEquals("Sketch", request.moduleName());
    Assertions.assertEquals("Alice", request.reviewOwner());
    Assertions.assertEquals("Document Review", request.reviewType());
    Assertions.assertEquals("Resolved", request.problemStatus());
    Assertions.assertEquals("Bob", request.reviewExpert());
    Assertions.assertEquals("{\"logic\":\"AND\",\"conditions\":[]}", request.filterGroupJson());
    Assertions.assertEquals("cc", request.sourceInstance());
    Assertions.assertEquals(2, request.page());
    Assertions.assertEquals(10, request.size());
    Assertions.assertEquals("updatedAt", request.sortField());
    Assertions.assertEquals("desc", request.sortOrder());
  }

  @Test
  void shouldUseDefaultPaginationWhenQueryParamsAreMissing() throws Exception {
    when(reviewDataRecordService.listRecords(any(ReviewDataRecordQueryRequest.class)))
        .thenReturn(
            new ReviewDataRecordListResponse(
                List.of(),
                0,
                1,
                20,
                "updatedAt",
                "desc",
                new ReviewDataSummaryResponse(0, 0, 0, 0)));

    mockMvc.perform(get("/api/review-data/records"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    var requestCaptor = org.mockito.ArgumentCaptor.forClass(ReviewDataRecordQueryRequest.class);
    verify(reviewDataRecordService).listRecords(requestCaptor.capture());
    ReviewDataRecordQueryRequest request = requestCaptor.getValue();
    Assertions.assertEquals(1, request.page());
    Assertions.assertEquals(20, request.size());
  }

  @Test
  void shouldReturnFilterOptions() throws Exception {
    when(reviewDataRecordService.getFilterOptions())
        .thenReturn(
            new ReviewDataFilterOptionsResponse(
                List.of(new OptionItemResponse("CrownCAD", "CrownCAD")),
                List.of(new OptionItemResponse("Sketch", "Sketch")),
                List.of(new OptionItemResponse("Alice", "Alice")),
                List.of(new OptionItemResponse("Document Review", "Document Review")),
                List.of(new OptionItemResponse("Bob", "Bob")),
                List.of(new OptionItemResponse("V1.0", "V1.0")),
                List.of(new OptionItemResponse("Resolved", "Resolved")),
                List.of(new OptionItemResponse("Meeting Review", "Meeting Review")),
                List.of(new OptionItemResponse("Formatting", "Formatting")),
                List.of(new OptionItemResponse("CC2026R1", "CC2026R1")),
                List.of(new OptionItemResponse("Sketch", "Sketch"))));

    mockMvc.perform(get("/api/review-data/records/filter-options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.projectNames[0].value").value("CrownCAD"))
        .andExpect(jsonPath("$.data.formProjectNames[0].value").value("CC2026R1"))
        .andExpect(jsonPath("$.data.reviewExperts[0].value").value("Bob"))
        .andExpect(jsonPath("$.data.reviewVersions[0].value").value("V1.0"));

    verify(reviewDataRecordService).getFilterOptions();
  }

  @Test
  void shouldReturnRecordDetail() throws Exception {
    when(reviewDataRecordService.getRecordDetail(1L))
        .thenReturn(
            new ReviewDataRecordDetailResponse(
                new ReviewDataRecordRowResponse(
                    1L,
                    "CrownCAD",
                    "Sketch Design Review",
                    "Sketch",
                    "Document Review",
                    LocalDate.of(2026, 4, 10),
                    "Alice",
                    "Bob, Carol",
                    24,
                    "Design Doc",
                    "Dora",
                    "V1.0",
                    5,
                    0.21,
                    LocalDateTime.of(2026, 4, 12, 10, 0),
                    false),
                List.of("Bob", "Carol"),
                List.of(
                    new ReviewDataProblemItemResponse(
                        9L,
                        1L,
                        "Bob",
                        0.5,
                        "Meeting Review",
                        "3.3.2",
                        "Formatting",
                        "Heading format is inconsistent",
                        "Unify heading format",
                        "Dora",
                        "",
                        "Resolved",
                        LocalDateTime.of(2026, 4, 12, 11, 0),
                        null)),
                List.of(),
                List.of()));

    mockMvc.perform(get("/api/review-data/records/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.record.projectName").value("CrownCAD"))
        .andExpect(jsonPath("$.data.reviewExperts[0]").value("Bob"))
        .andExpect(jsonPath("$.data.problemItems[0].problemDescription").value("Heading format is inconsistent"));
  }

  @Test
  void shouldExportReviewRecordWorkbook() throws Exception {
    when(excelExportService.exportReviewRecordsWorkbook(any(ReviewDataRecordQueryRequest.class)))
        .thenReturn(new byte[] {1, 2, 3});

    mockMvc.perform(get("/api/review-data/records/export").param("keyword", "review"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString(
                        "filename*=UTF-8''%E8%AF%84%E5%AE%A1%E6%95%B0%E6%8D%AE")));

    var requestCaptor = org.mockito.ArgumentCaptor.forClass(ReviewDataRecordQueryRequest.class);
    verify(excelExportService).exportReviewRecordsWorkbook(requestCaptor.capture());
    Assertions.assertEquals("review", requestCaptor.getValue().keyword());
  }

  @Test
  void shouldExportProblemDetailsWorkbookForRecord() throws Exception {
    when(excelExportService.exportProblemDetailsWorkbook(1L)).thenReturn(new byte[] {1, 2, 3});

    mockMvc.perform(get("/api/review-data/records/1/problem-items/export"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString(
                        "filename*=UTF-8''%E9%97%AE%E9%A2%98%E8%AF%A6%E6%83%85.xlsx")));

    verify(excelExportService).exportProblemDetailsWorkbook(1L);
  }

  @Test
  void shouldDownloadReviewTemplateWorkbook() throws Exception {
    when(templateWorkbookService.buildTemplateWorkbook()).thenReturn(new byte[] {1, 2, 3});

    mockMvc.perform(get("/api/review-data/template"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/vnd.ms-excel"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString(
                        "filename*=UTF-8''%E6%A8%A1%E6%9D%BF%E6%96%87%E4%BB%B6.xls")));

    verify(templateWorkbookService).buildTemplateWorkbook();
  }

  @Test
  void shouldCreateRecord() throws Exception {
    when(reviewDataRecordService.createRecord(
        any(ReviewDataRecordSaveRequest.class), any(String.class)))
        .thenReturn(
            new ReviewDataRecordDetailResponse(
                new ReviewDataRecordRowResponse(
                    12L,
                    "CrownCAD",
                    "New Review",
                    "Tools",
                    "Document Review",
                    LocalDate.of(2026, 4, 17),
                    "Alice",
                    "Bob",
                    18,
                    "Design Doc",
                    "Dora",
                    "V2.0",
                    0,
                    0.0,
                    LocalDateTime.of(2026, 4, 17, 10, 0),
                    false),
                List.of("Bob"),
                List.of(),
                List.of(),
                List.of()));

    mockMvc.perform(
            post("/api/review-data/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "projectName":"CrownCAD",
                      "title":"New Review",
                      "moduleName":"Tools",
                      "reviewType":"Document Review",
                      "reviewDate":"2026-04-17",
                      "reviewOwner":"Alice",
                      "reviewExperts":["Bob"],
                      "reviewScalePages":18,
                      "reviewProduct":"Design Doc",
                      "authorName":"Dora",
                      "reviewVersion":"V2.0"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.record.id").value(12));
  }

  @Test
  void shouldUpdateProblemItem() throws Exception {
    when(reviewDataRecordService.updateProblemItem(eq(1L), eq(9L), any(ReviewDataProblemItemSaveRequest.class)))
        .thenReturn(
            new ReviewDataProblemItemResponse(
                9L,
                1L,
                "Bob",
                0.8,
                "Meeting Review",
                "3.3.2",
                "Formatting",
                "Heading format is inconsistent",
                "Unify heading format",
                "Dora",
                "",
                "Resolved",
                LocalDateTime.of(2026, 4, 17, 11, 0),
                null));

    mockMvc.perform(
            put("/api/review-data/records/1/problem-items/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "reviewerName":"Bob",
                      "workloadHours":0.8,
                      "reviewCategory":"Meeting Review",
                      "documentPosition":"3.3.2",
                      "problemCategory":"Formatting",
                      "problemDescription":"Heading format is inconsistent",
                      "suggestedSolution":"Unify heading format",
                      "ownerName":"Dora",
                      "rejectionReason":"",
                      "problemStatus":"Resolved"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.problemStatus").value("Resolved"));
  }

  @Test
  void shouldDeleteRecord() throws Exception {
    mockMvc.perform(delete("/api/review-data/records/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  void legacyExcelPreviewShouldAcceptSupportedXlsFile() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "legacy.xls",
            "application/vnd.ms-excel",
            new byte[] {1, 2, 3});

    mockMvc.perform(multipart("/api/review-data/legacy-excel-import/preview").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  void legacyExcelPreviewShouldUseConfiguredUploadLimit() throws Exception {
    reviewDataProperties.setLegacyImportMaxBytes(3);
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "AllData.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new byte[] {1, 2, 3, 4});

    mockMvc.perform(multipart("/api/review-data/legacy-excel-import/preview").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1MB")))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("分批导出")));
  }
}
