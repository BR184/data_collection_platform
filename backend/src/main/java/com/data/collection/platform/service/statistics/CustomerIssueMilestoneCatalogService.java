package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeDimension;
import java.util.List;
import org.springframework.stereotype.Service;

/** 客户问题领域对统一议题范围目录的里程碑适配器。 */
@Service
public class CustomerIssueMilestoneCatalogService {
  private final IssueScopeCatalogService issueScopeCatalogService;

  public CustomerIssueMilestoneCatalogService(IssueScopeCatalogService issueScopeCatalogService) {
    this.issueScopeCatalogService = issueScopeCatalogService;
  }

  /** 返回按管理员顺序排列的项目 325 启用范围业务键。 */
  public List<String> listMilestones() {
    return issueScopeCatalogService.listEnabledBusinessKeys(
        IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID, IssueScopeDimension.MILESTONE);
  }

  /** 返回业务键和管理显示名称组成的下拉选项。 */
  public List<OptionItemResponse> listOptions() {
    return issueScopeCatalogService
        .listEnabledGroups(
            IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID, IssueScopeDimension.MILESTONE)
        .stream()
        .map(group -> new OptionItemResponse(group.displayName(), group.businessKey()))
        .toList();
  }

  /** 返回管理员排序中的第一条启用范围业务键。 */
  public String defaultMilestone() {
    return issueScopeCatalogService.defaultBusinessKey(
        IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID, IssueScopeDimension.MILESTONE);
  }

  /** 将客户问题范围业务键展开为精确里程碑事实值。 */
  public List<String> resolveMilestoneValues(String businessKey) {
    return issueScopeCatalogService.requireMemberValues(
        IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID,
        IssueScopeDimension.MILESTONE,
        businessKey);
  }

  /** 判断真实里程碑值是否属于指定客户问题范围。 */
  public boolean matches(String businessKey, String milestoneTitle) {
    return issueScopeCatalogService.matches(
        IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID,
        IssueScopeDimension.MILESTONE,
        businessKey,
        milestoneTitle);
  }

  /** 返回范围的管理显示名称；不存在时返回业务键本身。 */
  public String displayName(String businessKey) {
    return issueScopeCatalogService
        .findEnabledGroup(
            IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID,
            IssueScopeDimension.MILESTONE,
            businessKey)
        .map(IssueScopeCatalogService.ScopeGroup::displayName)
        .orElse(businessKey);
  }
}
