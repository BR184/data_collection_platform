package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import java.util.List;

record CustomerIssueDelayLabelWritebackPlan(
    IssueFact fact,
    List<String> currentLabels,
    CustomerIssueDelayLabelWritebackService.LabelChange change) {
  boolean hasChanges() {
    return change != null && !change.isEmpty();
  }
}
