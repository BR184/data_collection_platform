package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PagePermissionKeyResolver {
  private static final Map<String, String> BOARD_VIEW = Map.ofEntries(
      Map.entry("system-test-defect-summary", "system_test.summary.view"),
      Map.entry("system-test-delay-analysis", "system_test.delay.view"),
      Map.entry("system-test-defect-cause", "system_test.cause.view"),
      Map.entry("system-test-phase-statistics", "system_test.phase.view"),
      Map.entry("customer-issue-defect-summary", "customer_issue.summary.view"),
      Map.entry("customer-issue-defect-cause", "customer_issue.cause.view"),
      Map.entry("customer-issue-delay-issues", "customer_issue.delay.view"),
      Map.entry("customer-issue-response-efficiency", "customer_issue.efficiency.view"),
      Map.entry("customer-issue-by-function", "customer_issue.function.view"));

  private static final Map<String, String> BOARD_EXPORT = Map.ofEntries(
      Map.entry("system-test-defect-summary", "system_test.summary.export"),
      Map.entry("system-test-delay-analysis", "system_test.delay.export"),
      Map.entry("system-test-defect-cause", "system_test.cause.export"),
      Map.entry("customer-issue-defect-summary", "customer_issue.summary.export"),
      Map.entry("customer-issue-defect-cause", "customer_issue.cause.export"),
      Map.entry("customer-issue-delay-issues", "customer_issue.delay.export"),
      Map.entry("customer-issue-response-efficiency", "customer_issue.efficiency.export"),
      Map.entry("customer-issue-by-function", "customer_issue.function.export"));

  public String boardView(String boardKey) {
    return require(BOARD_VIEW, boardKey, "统计页面");
  }

  public String boardExport(String boardKey) {
    return require(BOARD_EXPORT, boardKey, "统计导出");
  }

  public String boardIssueExport(String boardKey) {
    return switch (boardKey) {
      case "system-test-defect-summary" -> "system_test.summary.export";
      case "customer-issue-defect-summary" -> "customer_issue.summary.export";
      default -> throw new BizException("当前统计页面不支持议题数据导出");
    };
  }

  public String dashboardView(String dashboardKey) {
    return switch (dashboardKey) {
      case "quality-rd" -> "quality.rd.view";
      case "quality-board-other" -> "quality.other.view";
      case "code-review-multi" -> "code_review.board.view";
      default -> throw new BizException("看板权限未定义: " + dashboardKey);
    };
  }

  public String dashboardExport(String dashboardKey) {
    return switch (dashboardKey) {
      case "quality-rd" -> "quality.rd.export";
      case "quality-board-other" -> "quality.other.export";
      case "code-review-multi" -> "code_review.board.export";
      default -> throw new BizException("看板导出权限未定义: " + dashboardKey);
    };
  }

  private String require(Map<String, String> values, String key, String label) {
    String permission = values.get(key);
    if (permission == null) {
      throw new BizException(label + "权限未定义: " + key);
    }
    return permission;
  }
}
