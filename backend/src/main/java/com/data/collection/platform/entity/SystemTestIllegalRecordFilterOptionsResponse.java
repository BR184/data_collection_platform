package com.data.collection.platform.entity;

import java.util.List;

public record SystemTestIllegalRecordFilterOptionsResponse(
    List<OptionItemResponse> projectNames,
    List<OptionItemResponse> moduleNames,
    List<OptionItemResponse> testingPhases,
    List<OptionItemResponse> illegalReasons,
    List<OptionItemResponse> authorNames,
    List<OptionItemResponse> assigneeNames,
    List<OptionItemResponse> issueStates,
    List<OptionItemResponse> severityLevels,
    List<OptionItemResponse> bugStatuses,
    List<OptionItemResponse> categories,
    List<OptionItemResponse> milestoneTitles) {}
