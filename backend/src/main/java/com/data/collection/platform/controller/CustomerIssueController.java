package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.service.CustomerIssueIllegalRecordService;
import com.data.collection.platform.service.CustomerIssueRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer-issues")
public class CustomerIssueController {
  private final CustomerIssueIllegalRecordService customerIssueIllegalRecordService;
  private final CustomerIssueRecordService customerIssueRecordService;

  public CustomerIssueController(
      CustomerIssueIllegalRecordService customerIssueIllegalRecordService,
      CustomerIssueRecordService customerIssueRecordService) {
    this.customerIssueIllegalRecordService = customerIssueIllegalRecordService;
    this.customerIssueRecordService = customerIssueRecordService;
  }

  @GetMapping("/records")
  public ApiResponse<CustomerIssueRecordListResponse> listRecords(
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String issueIid,
      @RequestParam(required = false) String title,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String moduleName,
      @RequestParam(required = false) String reasonCategory,
      @RequestParam(required = false) String severityLevel,
      @RequestParam(required = false) String priorityLevel,
      @RequestParam(required = false) String issueState,
      @RequestParam(required = false) String bugStatus,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String milestoneTitle,
      @RequestParam(required = false) String createdAtStart,
      @RequestParam(required = false) String createdAtEnd,
      @RequestParam(required = false) String updatedAtStart,
      @RequestParam(required = false) String updatedAtEnd,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortOrder) {
    return ApiResponse.success(
        customerIssueRecordService.listRecords(
            topic,
            projectId,
            keyword,
            issueIid,
            title,
            projectName,
            moduleName,
            reasonCategory,
            severityLevel,
            priorityLevel,
            issueState,
            bugStatus,
            category,
            milestoneTitle,
            createdAtStart,
            createdAtEnd,
            updatedAtStart,
            updatedAtEnd,
            page,
            size,
            sortBy,
            sortOrder));
  }

  @GetMapping("/records/filter-options")
  public ApiResponse<CustomerIssueRecordFilterOptionsResponse> getRecordFilterOptions(
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(customerIssueRecordService.getFilterOptions(topic, projectId));
  }

  @GetMapping("/records/rule-explanation")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getRecordRuleExplanation(
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(customerIssueRecordService.getRuleExplanation(topic, projectId));
  }

  @GetMapping("/illegal-records")
  public ApiResponse<CustomerIssueIllegalRecordListResponse> listIllegalRecords(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String issueIid,
      @RequestParam(required = false) String title,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String moduleName,
      @RequestParam(required = false) String illegalReason,
      @RequestParam(required = false) String severityLevel,
      @RequestParam(required = false) String priorityLevel,
      @RequestParam(required = false) String issueState,
      @RequestParam(required = false) String bugStatus,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String milestoneTitle,
      @RequestParam(required = false) String createdAtStart,
      @RequestParam(required = false) String createdAtEnd,
      @RequestParam(required = false) String updatedAtStart,
      @RequestParam(required = false) String updatedAtEnd,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortOrder) {
    return ApiResponse.success(
        customerIssueIllegalRecordService.listRecords(
            projectId,
            keyword,
            issueIid,
            title,
            projectName,
            moduleName,
            illegalReason,
            severityLevel,
            priorityLevel,
            issueState,
            bugStatus,
            category,
            milestoneTitle,
            createdAtStart,
            createdAtEnd,
            updatedAtStart,
            updatedAtEnd,
            page,
            size,
            sortBy,
            sortOrder));
  }

  @GetMapping("/illegal-records/filter-options")
  public ApiResponse<CustomerIssueIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(customerIssueIllegalRecordService.getFilterOptions(projectId));
  }

  @GetMapping("/illegal-records/rule-explanation")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(customerIssueIllegalRecordService.getRuleExplanation(projectId));
  }
}
