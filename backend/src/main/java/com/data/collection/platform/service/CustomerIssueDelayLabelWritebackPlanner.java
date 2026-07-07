package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueDelayLabelWritebackPlanner {
  private final CustomerIssueDelayLabelWritebackService writebackService;

  public CustomerIssueDelayLabelWritebackPlanner(CustomerIssueDelayLabelWritebackService writebackService) {
    this.writebackService = writebackService;
  }

  CustomerIssueDelayLabelWritebackPlan plan(IssueFact fact) {
    List<String> currentLabels = parseLabels(fact == null ? null : fact.getLabelNames());
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        writebackService.delayLabelChange(
            currentLabels,
            fact != null && Boolean.TRUE.equals(fact.getResponseDelayed()),
            fact != null && Boolean.TRUE.equals(fact.getResolveDelayed()));
    return new CustomerIssueDelayLabelWritebackPlan(fact, currentLabels, change);
  }

  List<String> parseLabels(String labelNames) {
    if (!StringUtils.hasText(labelNames)) {
      return List.of();
    }
    List<String> labels = new ArrayList<>();
    for (String part : labelNames.split(",")) {
      if (StringUtils.hasText(part)) {
        labels.add(part.trim());
      }
    }
    return labels;
  }
}
