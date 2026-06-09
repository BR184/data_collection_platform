package com.data.collection.platform.service;

import java.util.List;
import java.util.Map;

public interface SemanticQueryExecutor {
  List<SegmentMember> executeTemplate(String templateName, Map<String, Object> params);

  List<SegmentMember> executeDsl(SegmentRuleDsl dsl, SegmentExecutionContext context);

  SegmentExecutionPlan explain(SegmentRuleDsl dsl, SegmentExecutionContext context);
}
