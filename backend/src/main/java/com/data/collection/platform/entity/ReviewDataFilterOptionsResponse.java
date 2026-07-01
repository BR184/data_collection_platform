package com.data.collection.platform.entity;

import java.util.List;

public record ReviewDataFilterOptionsResponse(
    List<OptionItemResponse> projectNames,
    List<OptionItemResponse> moduleNames,
    List<OptionItemResponse> reviewOwners,
    List<OptionItemResponse> reviewTypes,
    List<OptionItemResponse> reviewExperts,
    List<OptionItemResponse> reviewVersions,
    List<OptionItemResponse> problemStatuses,
    List<OptionItemResponse> reviewCategories,
    List<OptionItemResponse> problemCategories,
    List<OptionItemResponse> formProjectNames,
    List<OptionItemResponse> formModuleNames) {}
