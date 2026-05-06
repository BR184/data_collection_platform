package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.database.DatabaseTableOption;
import com.data.collection.platform.entity.database.DatabaseTableRowsResponse;
import com.data.collection.platform.service.DatabaseBrowserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/database-browser")
// 数据库浏览控制器只提供受控表列表和分页查询，面向排查数据同步状态。
// 可浏览表、列和排序字段由服务层白名单决定，不开放任意 SQL 执行。
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
}
