package com.data.collection.platform.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.common.exception.GlobalRestExceptionHandler;
import com.data.collection.platform.entity.TagGroupResponse;
import com.data.collection.platform.entity.TagGroupValueResponse;
import com.data.collection.platform.entity.TagGroupsResponse;
import com.data.collection.platform.service.TagGroupService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class TagGroupControllerTest {
  @Mock private TagGroupService tagGroupService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TagGroupController(tagGroupService))
            .setControllerAdvice(new GlobalRestExceptionHandler())
            .build();
  }

  @Test
  void shouldReturnTagGroupsForDomain() throws Exception {
    when(tagGroupService.getTagGroups("issue"))
        .thenReturn(
            new TagGroupsResponse(
                "issue",
                "hash-123",
                List.of(
                    new TagGroupResponse(
                        "module",
                        "模块",
                        "multiple",
                        10,
                        "split_exact_comma",
                        List.of(
                            new TagGroupValueResponse(
                                "sketch",
                                "草图",
                                "standard",
                                1,
                                false,
                                null))))));

    mockMvc.perform(get("/api/tag-groups").param("domain", "issue"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.domain").value("issue"))
        .andExpect(jsonPath("$.data.schemaHash").value("hash-123"))
        .andExpect(jsonPath("$.data.groups[0].groupKey").value("module"))
        .andExpect(jsonPath("$.data.groups[0].selectionMode").value("multiple"))
        .andExpect(jsonPath("$.data.groups[0].matchStrategyName").value("split_exact_comma"))
        .andExpect(jsonPath("$.data.groups[0].values[0].valueKey").value("sketch"))
        .andExpect(jsonPath("$.data.groups[0].values[0].valueType").value("standard"));
  }

  @Test
  void shouldReloadTagMappingsFromAdminEndpoint() throws Exception {
    mockMvc.perform(post("/api/admin/reload-tag-mappings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    verify(tagGroupService).reload();
  }
}
