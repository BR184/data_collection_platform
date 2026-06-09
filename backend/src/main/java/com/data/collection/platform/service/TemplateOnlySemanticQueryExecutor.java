package com.data.collection.platform.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TemplateOnlySemanticQueryExecutor implements SemanticQueryExecutor {
  private static final int DEFAULT_MAX_EXECUTION_TIME_MS = 30_000;
  private static final int DEFAULT_MAX_ALLOWED_MEMBERS = 50_000;

  @Override
  public List<SegmentMember> executeTemplate(String templateName, Map<String, Object> params) {
    return List.of();
  }

  @Override
  public List<SegmentMember> executeDsl(SegmentRuleDsl dsl, SegmentExecutionContext context) {
    throw new UnsupportedOperationException("DSL execution is not enabled in phase 1");
  }

  @Override
  public SegmentExecutionPlan explain(SegmentRuleDsl dsl, SegmentExecutionContext context) {
    String templateName = templateNameFrom(dsl);
    return new SegmentExecutionPlan(
        templateName, 0, 0, DEFAULT_MAX_EXECUTION_TIME_MS, DEFAULT_MAX_ALLOWED_MEMBERS);
  }

  private static String templateNameFrom(SegmentRuleDsl dsl) {
    if (dsl == null || dsl.rawJson() == null || dsl.rawJson().isBlank()) {
      return "empty_template";
    }
    String raw = dsl.rawJson();
    String marker = "\"templateName\"";
    int markerIndex = raw.indexOf(marker);
    if (markerIndex < 0) {
      return "phase_one_template";
    }
    int colonIndex = raw.indexOf(':', markerIndex + marker.length());
    int firstQuote = raw.indexOf('"', colonIndex + 1);
    int secondQuote = raw.indexOf('"', firstQuote + 1);
    if (colonIndex < 0 || firstQuote < 0 || secondQuote < 0) {
      return "phase_one_template";
    }
    return raw.substring(firstQuote + 1, secondQuote);
  }
}
