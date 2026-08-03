package com.data.collection.platform.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerIssueRecordFilterOptionsResponseTest {

  @Test
  void shouldNormalizeMissingSnapshotOptionFieldsToEmptyLists() {
    CustomerIssueRecordFilterOptionsResponse response =
        new CustomerIssueRecordFilterOptionsResponse(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(
            List.of(
                response.projectNames(),
                response.moduleNames(),
                response.functionNames(),
                response.customerNames(),
                response.reasonCategories(),
                response.severityLevels(),
                response.priorityLevels(),
                response.issueStates(),
                response.bugStatuses(),
                response.categories(),
                response.authorNames(),
                response.handlerNames(),
                response.assigneeNames(),
                response.testingPhases(),
                response.fixUsers(),
                response.delayCauses(),
                response.plannedMergeVersionBranches(),
                response.milestoneTitles()))
        .allSatisfy(options -> assertThat(options).isEmpty());
  }
}
