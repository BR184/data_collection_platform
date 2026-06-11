package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRulePreviewResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberResponse;
import com.data.collection.platform.service.IssueFactRecord;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.data.collection.platform.service.TextQuerySupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDynamicRuleEvaluationService {
  private static final int MAX_OUTPUT_VALUES = LabelGroupService.MAX_MEMBER_COUNT;

  private final IssueFactRecordRepository issueFactRecordRepository;
  private final ObjectMapper objectMapper;

  public LabelGroupDynamicRuleEvaluationService(
      IssueFactRecordRepository issueFactRecordRepository,
      ObjectMapper objectMapper) {
    this.issueFactRecordRepository = issueFactRecordRepository;
    this.objectMapper = objectMapper;
  }

  public LabelGroupDynamicRulePreviewResponse preview(String ruleTemplateKey, String ruleParamsJson) {
    String templateKey = requireTemplateKey(ruleTemplateKey);
    List<String> values = evaluate(templateKey, ruleParamsJson);
    return new LabelGroupDynamicRulePreviewResponse(
        templateKey,
        outputValueType(templateKey),
        "SUCCESS",
        values.isEmpty() ? "当前规则未计算出成员" : "已计算出 " + values.size() + " 个成员",
        values.stream()
            .map(value -> new LabelGroupMemberResponse(null, value, value, true, 0))
            .toList());
  }

  public List<LabelGroupMemberRecord> materializeMembers(String ruleTemplateKey, String ruleParamsJson) {
    List<String> values = evaluate(requireTemplateKey(ruleTemplateKey), ruleParamsJson);
    int sortOrder = 0;
    java.util.ArrayList<LabelGroupMemberRecord> members = new java.util.ArrayList<>();
    for (String value : values) {
      members.add(new LabelGroupMemberRecord(null, null, value, value, sortOrder++));
    }
    return members;
  }

  public String outputValueType(String ruleTemplateKey) {
    return switch (requireTemplateKey(ruleTemplateKey)) {
      case "recent-active-assignee", "current-version-delayed-assignee" -> LabelGroupService.TYPE_STRING;
      default -> throw new BizException("动态规则模板不存在：" + ruleTemplateKey);
    };
  }

  private List<String> evaluate(String templateKey, String ruleParamsJson) {
    Map<String, Object> params = parseParams(ruleParamsJson);
    List<IssueFactRecord> rows = issueFactRecordRepository.findByFilters(Map.of());
    List<IssueFactRecord> scopedRows = applyScope(rows, stringParam(params, "scope"));
    List<IssueFactRecord> filteredRows =
        switch (templateKey) {
          case "recent-active-assignee" -> recentActiveRows(scopedRows, intParam(params, "days", 30));
          case "current-version-delayed-assignee" -> scopedRows.stream().filter(IssueFactRecord::delayRelated).toList();
          default -> throw new BizException("动态规则模板不存在：" + templateKey);
        };
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (IssueFactRecord row : filteredRows) {
      String assigneeName = TextQuerySupport.trimToNull(row.assigneeName());
      if (assigneeName != null) {
        values.add(assigneeName);
      }
      if (values.size() > MAX_OUTPUT_VALUES) {
        throw new BizException("动态规则输出超过 200 个成员，请缩小规则范围");
      }
    }
    return List.copyOf(values);
  }

  private List<IssueFactRecord> applyScope(List<IssueFactRecord> rows, String scope) {
    return switch (scope == null ? "system-test" : scope) {
      case "system-test" -> rows.stream().filter(row -> row.primaryPhaseLabel() != null && !row.primaryPhaseLabel().isBlank()).toList();
      case "customer-issues" -> rows.stream().filter(row -> "CC_Product".equalsIgnoreCase(row.projectName())).toList();
      case "all" -> rows;
      default -> throw new BizException("动态规则数据范围不支持：" + scope);
    };
  }

  private List<IssueFactRecord> recentActiveRows(List<IssueFactRecord> rows, int days) {
    LocalDateTime threshold = LocalDateTime.now().minusDays(days);
    return rows.stream()
        .filter(row -> row.updatedAt() != null && !row.updatedAt().isBefore(threshold))
        .toList();
  }

  private Map<String, Object> parseParams(String ruleParamsJson) {
    String normalized = TextQuerySupport.trimToNull(ruleParamsJson);
    if (normalized == null) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(normalized, new TypeReference<>() {});
    } catch (Exception error) {
      throw new BizException("动态规则参数格式不正确");
    }
  }

  private String requireTemplateKey(String ruleTemplateKey) {
    String templateKey = TextQuerySupport.trimToNull(ruleTemplateKey);
    if (templateKey == null) {
      throw new BizException("动态规则模板不能为空");
    }
    return templateKey;
  }

  private String stringParam(Map<String, Object> params, String key) {
    Object value = params.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private int intParam(Map<String, Object> params, String key, int defaultValue) {
    Object value = params.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return Math.max(1, number.intValue());
    }
    try {
      return Math.max(1, Integer.parseInt(String.valueOf(value)));
    } catch (NumberFormatException error) {
      throw new BizException("动态规则参数必须是数字：" + key);
    }
  }
}
