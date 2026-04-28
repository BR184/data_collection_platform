package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.service.CustomerIssueIllegalRecordService;
import com.data.collection.platform.service.CustomerIssueRecordService;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer-issues")
public class CustomerIssueController {
  private final CustomerIssueIllegalRecordService customerIssueIllegalRecordService;
  private final CustomerIssueRecordService customerIssueRecordService;
  private final CustomerIssueRequestAssembler customerIssueRequestAssembler;

  public CustomerIssueController(
      CustomerIssueIllegalRecordService customerIssueIllegalRecordService,
      CustomerIssueRecordService customerIssueRecordService,
      CustomerIssueRequestAssembler customerIssueRequestAssembler) {
    this.customerIssueIllegalRecordService = customerIssueIllegalRecordService;
    this.customerIssueRecordService = customerIssueRecordService;
    this.customerIssueRequestAssembler = customerIssueRequestAssembler;
  }

  @GetMapping("/records")
  public ApiResponse<CustomerIssueRecordListResponse> listRecords(
      @ModelAttribute CustomerIssueRecordListWebRequest request) {
    return ApiResponse.success(
        customerIssueRecordService.listRecords(
            customerIssueRequestAssembler.toRecordQueryRequest(request)));
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
      @ModelAttribute CustomerIssueIllegalRecordListWebRequest request) {
    return ApiResponse.success(
        customerIssueIllegalRecordService.listRecords(
            customerIssueRequestAssembler.toIllegalRecordQueryRequest(request)));
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
