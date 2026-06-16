package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupRuleConditionGroupRequest(
    String logic,
    List<LabelGroupRuleConditionRequest> conditions,
    List<LabelGroupRuleConditionGroupRequest> groups) {}
