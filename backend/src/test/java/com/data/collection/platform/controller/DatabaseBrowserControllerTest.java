package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.database.DatabaseTableColumn;
import com.data.collection.platform.entity.database.DatabaseTableOption;
import com.data.collection.platform.entity.database.DatabaseTableRowsResponse;
import com.data.collection.platform.service.DatabaseBrowserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DatabaseBrowserControllerTest {

  @Mock
  private DatabaseBrowserService databaseBrowserService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new DatabaseBrowserController(databaseBrowserService)).build();
  }

  @Test
  void shouldReturnWhitelistedTables() throws Exception {
    when(databaseBrowserService.listTables()).thenReturn(List.of(
        new DatabaseTableOption("gitlab_sync_tasks", "同步任务", "IDLE", null),
        new DatabaseTableOption("gitlab_mirror_records", "旧镜像记录", "IDLE", null)));

    mockMvc.perform(get("/api/database-browser/tables"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].tableName").value("gitlab_sync_tasks"))
        .andExpect(jsonPath("$.data[0].label").value("同步任务"));
  }

  @Test
  void shouldReturnRowsPayload() throws Exception {
    when(databaseBrowserService.getTableRows("gitlab_sync_logs", 1, 20, "FAILED", "id", "desc"))
        .thenReturn(new DatabaseTableRowsResponse(
            "gitlab_sync_logs",
            "同步日志",
            List.of(
                new DatabaseTableColumn("id", "Id", true),
                new DatabaseTableColumn("status", "Status", true)),
            List.of(Map.of("id", 1, "status", "FAILED")),
            1,
            1,
            20,
            "id",
            "desc",
            "FAILED",
            "IDLE",
            null,
            null));

    mockMvc.perform(get("/api/database-browser/rows")
            .param("tableName", "gitlab_sync_logs")
            .param("page", "1")
            .param("size", "20")
            .param("keyword", "FAILED")
            .param("sortField", "id")
            .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.tableName").value("gitlab_sync_logs"))
        .andExpect(jsonPath("$.data.label").value("同步日志"))
        .andExpect(jsonPath("$.data.rows[0].status").value("FAILED"));
  }

  @Test
  void shouldRefreshCurrentTable() throws Exception {
    when(databaseBrowserService.refreshTable("ods_gitlab_issues_cc")).thenReturn(1);

    mockMvc.perform(post("/api/database-browser/refresh")
            .param("tableName", "ods_gitlab_issues_cc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.plannedTasks").value(1));
  }
}
