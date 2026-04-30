package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.service.CustomerIssueIllegalRecordService;
import com.data.collection.platform.service.CustomerIssueRecordService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

  @GetMapping("/records/export")
  public ResponseEntity<String> exportRecords(
      @ModelAttribute CustomerIssueRecordListWebRequest request) {
    String csv =
        customerIssueRecordService.exportRecordsCsv(
            customerIssueRequestAssembler.toRecordQueryRequest(request));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customer-issue-records.csv\"")
        .body(csv);
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

  @GetMapping("/illegal-records/export")
  public ResponseEntity<String> exportIllegalRecords(
      @ModelAttribute CustomerIssueIllegalRecordListWebRequest request) {
    String csv =
        customerIssueIllegalRecordService.exportRecordsCsv(
            customerIssueRequestAssembler.toIllegalRecordQueryRequest(request));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customer-issue-illegal-records.csv\"")
        .body(csv);
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
