package com.data.collection.platform.entity.labelgroup;

import com.data.collection.platform.entity.OptionItemResponse;
import java.util.List;

public record LabelGroupDynamicRuleSourceFieldResponse(
    String key,
    String name,
    String valueType,
    boolean outputSupported,
    boolean filterSupported,
    boolean groupSupported,
    boolean aggregateSupported,
    List<String> operators,
    String candidateMode,
    List<OptionItemResponse> candidateValues) {}
