package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberResponse;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupExpansionService {
  private final LabelGroupService labelGroupService;

  public LabelGroupExpansionService(LabelGroupService labelGroupService) {
    this.labelGroupService = labelGroupService;
  }

  public LabelGroupExpansionResponse expand(
      Long groupId, String fieldValueType, String fieldKey, String pageKey, String sourceInstanceId) {
    LabelGroupService.ExpandedLabelGroup expanded = labelGroupService.expandValues(groupId);
    String expectedValueType = normalizeValueType(fieldValueType);
    if (expectedValueType != null
        && expanded.valueType() != null
        && !expectedValueType.equals(expanded.valueType())) {
      throw new BizException("字段值类型与标签组值类型不兼容");
    }

    LinkedHashSet<String> values = new LinkedHashSet<>(expanded.values());
    if ("closure_status".equals(fieldKey) && values.contains("需求如此")) {
      values.add("设计如此");
    }
    if (values.size() > LabelGroupService.MAX_MEMBER_COUNT) {
      throw new BizException("标签组展开后超过 200 个值，请拆分后保存");
    }
    List<LabelGroupMemberResponse> members =
        values.stream()
            .map(value -> new LabelGroupMemberResponse(null, value, value, true, 0))
            .toList();
    return new LabelGroupExpansionResponse(
        expanded.groupId(), expanded.groupName(), expanded.valueType(), List.copyOf(values), members);
  }

  private String normalizeValueType(String value) {
    return value == null || value.isBlank() ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
  }
}
