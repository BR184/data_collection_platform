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
  private final LabelDimensionCatalogService dimensionCatalogService;

  public LabelGroupExpansionService(
      LabelGroupService labelGroupService,
      LabelDimensionCatalogService dimensionCatalogService) {
    this.labelGroupService = labelGroupService;
    this.dimensionCatalogService = dimensionCatalogService;
  }

  public LabelGroupExpansionResponse expand(
      Long groupId, String dimensionKey, String pageKey, String sourceInstanceId) {
    LabelGroupRecord group = labelGroupService.requireGroup(groupId);
    LabelDimensionDefinition dimension = dimensionCatalogService.getDimension(group.dimensionKey());
    if (dimensionKey != null && !dimensionKey.isBlank() && !group.dimensionKey().equals(dimensionKey)) {
      throw new BizException("标签组只能应用到相同维度的字段");
    }
    if (!dimensionCatalogService.pageSupportsDimension(group.dimensionKey(), pageKey)) {
      throw new BizException("当前页面不支持该标签维度：" + dimension.name());
    }
    if (!group.enabled()) {
      throw new BizException("标签组已禁用，不能应用筛选");
    }
    if (group.members().isEmpty()) {
      throw new BizException("该标签组当前没有可用成员");
    }

    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (LabelGroupMemberRecord member : group.members()) {
      if (!group.dimensionKey().equals(member.dimensionKey())) {
        throw new BizException("标签组成员维度与标签组不一致");
      }
      values.add(member.memberValue());
      if ("closure_status".equals(group.dimensionKey()) && "需求如此".equals(member.memberValue())) {
        values.add("设计如此");
      }
    }
    if (values.isEmpty()) {
      throw new BizException("该标签组当前没有可用成员");
    }
    List<LabelGroupMemberResponse> members =
        group.members().stream()
            .map(member -> new LabelGroupMemberResponse(
                member.id(), member.memberValue(), member.displayName(), true, member.sortOrder()))
            .toList();
    return new LabelGroupExpansionResponse(
        group.id(), group.dimensionKey(), dimension.name(), List.copyOf(values), members);
  }
}
