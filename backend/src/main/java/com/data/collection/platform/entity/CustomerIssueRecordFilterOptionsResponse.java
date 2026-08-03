package com.data.collection.platform.entity;

import java.util.List;

public record CustomerIssueRecordFilterOptionsResponse(
    List<OptionItemResponse> projectNames,
    List<OptionItemResponse> moduleNames,
    List<OptionItemResponse> functionNames,
    List<OptionItemResponse> customerNames,
    List<OptionItemResponse> reasonCategories,
    List<OptionItemResponse> severityLevels,
    List<OptionItemResponse> priorityLevels,
    List<OptionItemResponse> issueStates,
    List<OptionItemResponse> bugStatuses,
    List<OptionItemResponse> categories,
    List<OptionItemResponse> authorNames,
    List<OptionItemResponse> handlerNames,
    List<OptionItemResponse> assigneeNames,
    List<OptionItemResponse> testingPhases,
    List<OptionItemResponse> fixUsers,
    List<OptionItemResponse> delayCauses,
    List<OptionItemResponse> plannedMergeVersionBranches,
    List<OptionItemResponse> milestoneTitles) {

  public CustomerIssueRecordFilterOptionsResponse {
    projectNames = copyOptions(projectNames);
    moduleNames = copyOptions(moduleNames);
    functionNames = copyOptions(functionNames);
    customerNames = copyOptions(customerNames);
    reasonCategories = copyOptions(reasonCategories);
    severityLevels = copyOptions(severityLevels);
    priorityLevels = copyOptions(priorityLevels);
    issueStates = copyOptions(issueStates);
    bugStatuses = copyOptions(bugStatuses);
    categories = copyOptions(categories);
    authorNames = copyOptions(authorNames);
    handlerNames = copyOptions(handlerNames);
    assigneeNames = copyOptions(assigneeNames);
    testingPhases = copyOptions(testingPhases);
    fixUsers = copyOptions(fixUsers);
    delayCauses = copyOptions(delayCauses);
    plannedMergeVersionBranches = copyOptions(plannedMergeVersionBranches);
    milestoneTitles = copyOptions(milestoneTitles);
  }

  private static List<OptionItemResponse> copyOptions(List<OptionItemResponse> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
