package com.data.collection.platform.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.CollectFormDetailResponse;
import com.data.collection.platform.entity.CollectFormEditContext;
import com.data.collection.platform.entity.CollectFormNotificationPayloadResponse;
import com.data.collection.platform.service.CollectFormService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CollectFormControllerTest {

  @Mock
  private CollectFormService collectFormService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new CollectFormController(collectFormService)).build();
  }

  @Test
  void detailShouldReturnPlatformRecord() throws Exception {
    when(collectFormService.getDetail(
        eq("http://172.22.10.233"),
        eq(88L),
        eq("merge_request"),
        eq("12"),
        eq("code_review")))
        .thenReturn(new CollectFormDetailResponse(
            7L,
            "http://172.22.10.233",
            88L,
            12L,
            "merge_request",
            "12",
            "code_review",
            "代码走查表",
            "王小欢",
            5,
            1,
            2,
            3,
            4,
            5,
            "备注",
            false,
            LocalDateTime.now(),
            LocalDateTime.now()));

    mockMvc.perform(get("/api/collect-forms/detail")
            .param("gitlabBaseUrl", "http://172.22.10.233")
            .param("projectId", "88")
            .param("resourceType", "merge_request")
            .param("resourceId", "12")
            .param("templateCode", "code_review"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.projectId").value(88))
        .andExpect(jsonPath("$.data.requestIid").value(12))
        .andExpect(jsonPath("$.data.resourceId").value("12"))
        .andExpect(jsonPath("$.data.formTitle").value("代码走查表"));
  }

  @Test
  void notificationPayloadShouldReturnFourCoreFields() throws Exception {
    when(collectFormService.buildNotificationPayload(
        eq("http://172.22.10.233"),
        eq(88L),
        eq(12L),
        eq("merge_request")))
        .thenReturn(new CollectFormNotificationPayloadResponse(
            "http://172.22.10.233",
            88L,
            12L,
            "merge_request"));

    mockMvc.perform(get("/api/collect-forms/notification-payload")
            .param("gitlabBaseUrl", "http://172.22.10.233")
            .param("projectId", "88")
            .param("requestIid", "12")
            .param("resourceType", "merge_request"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.sourceAddress").value("http://172.22.10.233"))
        .andExpect(jsonPath("$.data.projectId").value(88))
        .andExpect(jsonPath("$.data.requestIid").value(12))
        .andExpect(jsonPath("$.data.resourceType").value("merge_request"));
  }

  @Test
  void saveShouldReturnSavedRecordPayload() throws Exception {
    when(collectFormService.save(
        eq("http://172.22.10.233"),
        eq(88L),
        eq(12L),
        eq("merge_request"),
        eq("12"),
        eq("code_review"),
        eq("代码走查表"),
        eq("王小欢"),
        eq(5),
        eq(1),
        eq(2),
        eq(3),
        eq(4),
        eq(5),
        eq("备注"),
        any(CollectFormEditContext.class)))
        .thenReturn(new CollectFormDetailResponse(
            7L,
            "http://172.22.10.233",
            88L,
            12L,
            "merge_request",
            "12",
            "code_review",
            "代码走查表",
            "王小欢",
            5,
            1,
            2,
            3,
            4,
            5,
            "备注",
            false,
            LocalDateTime.now(),
            LocalDateTime.now()));

    mockMvc.perform(post("/api/collect-forms/save")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "gitlabBaseUrl": "http://172.22.10.233",
                  "projectId": 88,
                  "requestIid": 12,
                  "resourceType": "merge_request",
                  "resourceId": "12",
                  "templateCode": "code_review",
                  "formTitle": "代码走查表",
                  "reviewer": "王小欢",
                  "reviewDurationMinutes": 5,
                  "specificationScore": 1,
                  "logicScore": 2,
                  "performanceScore": 3,
                  "designScore": 4,
                  "otherScore": 5,
                  "remark": "备注"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.templateCode").value("code_review"))
        .andExpect(jsonPath("$.data.requestIid").value(12))
        .andExpect(jsonPath("$.data.reviewer").value("王小欢"));
  }

  @Test
  void saveShouldRejectInvalidBodyBeforeCallingService() throws Exception {
    mockMvc.perform(post("/api/collect-forms/save")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "gitlabBaseUrl": "http://172.22.10.233",
                  "projectId": 88,
                  "resourceType": "",
                  "resourceId": "12",
                  "templateCode": "code_review",
                  "reviewDurationMinutes": -1
                }
                """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(collectFormService);
  }

  @Test
  void updateRecordShouldReturnUpdatedPayload() throws Exception {
    when(collectFormService.updateRecord(
        eq(7L),
        eq("代码走查表-改"),
        eq("李测试"),
        eq(8),
        eq(2),
        eq(3),
        eq(4),
        eq(5),
        eq(6),
        eq("数据库查看修改"),
        eq(true),
        any(CollectFormEditContext.class)))
        .thenReturn(new CollectFormDetailResponse(
            7L,
            "http://172.22.10.233",
            88L,
            12L,
            "merge_request",
            "12",
            "code_review",
            "代码走查表-改",
            "李测试",
            8,
            2,
            3,
            4,
            5,
            6,
            "数据库查看修改",
            true,
            LocalDateTime.now(),
            LocalDateTime.now()));

    mockMvc.perform(post("/api/collect-forms/update-record")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "id": 7,
                  "formTitle": "代码走查表-改",
                  "reviewer": "李测试",
                  "reviewDurationMinutes": 8,
                  "specificationScore": 2,
                  "logicScore": 3,
                  "performanceScore": 4,
                  "designScore": 5,
                  "otherScore": 6,
                  "remark": "数据库查看修改",
                  "deleted": true
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(7))
        .andExpect(jsonPath("$.data.formTitle").value("代码走查表-改"))
        .andExpect(jsonPath("$.data.deleted").value(true));
  }

  @Test
  void updateRecordShouldRejectMissingIdBeforeCallingService() throws Exception {
    mockMvc.perform(post("/api/collect-forms/update-record")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "formTitle": "Review Form",
                  "reviewDurationMinutes": 8
                }
                """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(collectFormService);
  }
}
