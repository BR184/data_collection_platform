package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleTemplateOptionResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleTemplateParameterResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleTemplateResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDynamicRuleTemplateService {
  private static final List<LabelGroupDynamicRuleTemplateOptionResponse> DATA_SCOPE_OPTIONS =
      List.of(
          new LabelGroupDynamicRuleTemplateOptionResponse("系统测试议题", "system-test"),
          new LabelGroupDynamicRuleTemplateOptionResponse("客户问题", "customer-issues"),
          new LabelGroupDynamicRuleTemplateOptionResponse("全部", "all"));

  public List<LabelGroupDynamicRuleTemplateResponse> listTemplates() {
    return List.of(
        new LabelGroupDynamicRuleTemplateResponse(
            "recent-active-assignee",
            "最近 N 天活跃处理人",
            "从指定数据范围内最近 N 天有处理记录的议题中，计算处理人字符串列表。",
            LabelGroupService.TYPE_STRING,
            "输出：处理人字符串列表",
            List.of(
                new LabelGroupDynamicRuleTemplateParameterResponse(
                    "days", "最近天数", "number", true, 30, List.of()),
                new LabelGroupDynamicRuleTemplateParameterResponse(
                    "scope", "数据范围", "select", true, "system-test", DATA_SCOPE_OPTIONS))),
        new LabelGroupDynamicRuleTemplateResponse(
            "current-version-delayed-assignee",
            "当前版本延期处理人",
            "从当前版本延期问题中，计算处理人字符串列表。",
            LabelGroupService.TYPE_STRING,
            "输出：处理人字符串列表",
            List.of(
                new LabelGroupDynamicRuleTemplateParameterResponse(
                    "scope", "数据范围", "select", true, "system-test", DATA_SCOPE_OPTIONS))));
  }
}
