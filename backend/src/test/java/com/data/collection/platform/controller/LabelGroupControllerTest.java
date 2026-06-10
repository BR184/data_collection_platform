package com.data.collection.platform.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.GlobalRestExceptionHandler;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValueResponse;
import com.data.collection.platform.service.labelgroup.LabelDimensionCatalogService;
import com.data.collection.platform.service.labelgroup.LabelValueQueryService;
import com.data.collection.platform.service.labelgroup.LabelValueKind;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class LabelGroupControllerTest {

  @Mock private LabelValueQueryService labelValueQueryService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new LabelGroupController(new LabelDimensionCatalogService(), labelValueQueryService))
            .setControllerAdvice(new GlobalRestExceptionHandler())
            .build();
  }

  @Test
  void shouldReturnDimensions() throws Exception {
    mockMvc.perform(get("/api/label-groups/dimensions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].key").value("module"))
        .andExpect(jsonPath("$.data[0].name").value("模块"))
        .andExpect(jsonPath("$.data[0].valueKind").value("STRING_LITERAL"));
  }

  @Test
  void shouldReturnDimensionValuesWithExplicitScopeParameters() throws Exception {
    when(labelValueQueryService.listValues(eq("module"), eq("review-data-home"), eq("cc"), eq("草"), eq(1), eq(20)))
        .thenReturn(
            new LabelValuePageResponse(
                List.of(new LabelValueResponse("草图", "草图", LabelValueKind.STRING_LITERAL, "FACT", 12)),
                1,
                1,
                20));

    mockMvc.perform(
            get("/api/label-groups/dimensions/module/values")
                .param("pageKey", "review-data-home")
                .param("sourceInstanceId", "cc")
                .param("keyword", "草")
                .param("page", "1")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items[0].value").value("草图"))
        .andExpect(jsonPath("$.data.items[0].label").value("草图"))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void shouldReturnCompatiblePages() throws Exception {
    mockMvc.perform(get("/api/label-groups/dimensions/review_owner/compatible-pages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].pageKey").value("review-data-home"))
        .andExpect(jsonPath("$.data[0].pageName").value("评审数据管理"));
  }

  @Test
  void shouldReturnChineseErrorForUnknownDimension() throws Exception {
    when(labelValueQueryService.listValues(eq("missing"), eq(null), eq(null), eq(null), eq(1), eq(20)))
        .thenThrow(new BizException("标签维度不存在：missing"));

    mockMvc.perform(get("/api/label-groups/dimensions/missing/values"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("标签维度不存在：missing"));
  }
}
