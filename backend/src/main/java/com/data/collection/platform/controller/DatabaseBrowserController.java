package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.database.DatabaseTableOption;
import com.data.collection.platform.entity.database.DatabaseTableRowsResponse;
import com.data.collection.platform.service.DatabaseBrowserService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/database-browser")
public class DatabaseBrowserController {

  private final DatabaseBrowserService databaseBrowserService;

  public DatabaseBrowserController(DatabaseBrowserService databaseBrowserService) {
    this.databaseBrowserService = databaseBrowserService;
  }

  @GetMapping("/tables")
  public ApiResponse<List<DatabaseTableOption>> listTables() {
    return ApiResponse.success(databaseBrowserService.listTables());
  }

  @GetMapping("/rows")
  public ApiResponse<DatabaseTableRowsResponse> getTableRows(
      @RequestParam @NotBlank String tableName,
      @RequestParam(defaultValue = "1") @Positive int page,
      @RequestParam(defaultValue = "20") @Positive int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sortField,
      @RequestParam(required = false) String sortOrder) {
    return ApiResponse.success(
        databaseBrowserService.getTableRows(tableName, page, size, keyword, sortField, sortOrder));
  }

  @PostMapping("/refresh")
  public ApiResponse<Map<String, Object>> refreshTable(@RequestParam @NotBlank String tableName) {
    GitlabMirrorSyncService.OnDemandRefreshResult result =
        databaseBrowserService.refreshTableDetailed(tableName);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accepted", result.plannedTasks() > 0);
    payload.put("runId", result.jobId());
    payload.put("status", result.status());
    payload.put("message", result.message());
    payload.put("sourceTables", result.sourceTables());
    payload.put("plannedTasks", result.plannedTasks());
    return ApiResponse.success(result.message(), payload);
  }
}
