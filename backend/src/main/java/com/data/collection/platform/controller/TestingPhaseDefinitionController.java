package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.TestingPhaseDefinitionResponse;
import com.data.collection.platform.entity.TestingPhaseDefinitionSaveRequest;
import com.data.collection.platform.entity.TestingPhaseProjectOptionResponse;
import com.data.collection.platform.service.TestingPhaseDefinitionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/testing-phases")
public class TestingPhaseDefinitionController {

  private final TestingPhaseDefinitionService testingPhaseDefinitionService;

  public TestingPhaseDefinitionController(TestingPhaseDefinitionService testingPhaseDefinitionService) {
    this.testingPhaseDefinitionService = testingPhaseDefinitionService;
  }

  @GetMapping
  public ApiResponse<List<TestingPhaseDefinitionResponse>> list(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean enabled) {
    return ApiResponse.success(testingPhaseDefinitionService.list(projectId, keyword, enabled));
  }

  @GetMapping("/project-options")
  public ApiResponse<List<TestingPhaseProjectOptionResponse>> listProjectOptions() {
    return ApiResponse.success(testingPhaseDefinitionService.listProjectOptions());
  }

  @PostMapping
  public ApiResponse<TestingPhaseDefinitionResponse> create(
      @RequestBody @Valid TestingPhaseDefinitionSaveRequest request) {
    return ApiResponse.success("测试阶段定义已保存", testingPhaseDefinitionService.create(request));
  }

  @PutMapping("/{id}")
  public ApiResponse<TestingPhaseDefinitionResponse> update(
      @PathVariable Long id, @RequestBody @Valid TestingPhaseDefinitionSaveRequest request) {
    return ApiResponse.success("测试阶段定义已更新", testingPhaseDefinitionService.update(id, request));
  }

  @PatchMapping("/{id}/enabled")
  public ApiResponse<TestingPhaseDefinitionResponse> setEnabled(
      @PathVariable Long id, @RequestBody EnabledRequest request) {
    return ApiResponse.success(
        "测试阶段状态已更新",
        testingPhaseDefinitionService.setEnabled(id, request.enabled()));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    testingPhaseDefinitionService.delete(id);
    return ApiResponse.success("测试阶段定义已删除", null);
  }

  public record EnabledRequest(boolean enabled) {}
}
