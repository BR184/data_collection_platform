package com.data.collection.platform.service;

/** 客户问题记录页的字段筛选契约。 */
public record CustomerIssueRecordFilters(
    String reasonCategory,
    String authorName,
    String handlerName,
    String assigneeName,
    String testingPhase,
    String fixUser,
    String delayCause,
    CcProductFilters ccProduct) {

  public CustomerIssueRecordFilters {
    ccProduct = ccProduct == null ? CcProductFilters.empty() : ccProduct;
  }

  /**
   * 返回无字段约束的客户问题记录筛选。
   *
   * @return 不包含任何筛选值的不可变契约
   */
  public static CustomerIssueRecordFilters empty() {
    return new CustomerIssueRecordFilters(
        null, null, null, null, null, null, null, CcProductFilters.empty());
  }

  /** CC_PRODUCT 议题独有的客户、响应计划与动态滞留筛选。 */
  public record CcProductFilters(
      String customerName,
      String plannedResolutionAtStart,
      String plannedResolutionAtEnd,
      String plannedMergeVersionBranch,
      Long retentionHoursMin,
      Long retentionHoursMax) {

    /**
     * 返回无约束的 CC_PRODUCT 专属筛选。
     *
     * @return 不包含任何筛选值的不可变契约
     */
    public static CcProductFilters empty() {
      return new CcProductFilters(null, null, null, null, null, null);
    }

    /**
     * 判断请求是否按动态滞留小时限制结果成员。
     *
     * @return 任一滞留边界存在时返回 {@code true}
     */
    public boolean hasRetentionRange() {
      return retentionHoursMin != null || retentionHoursMax != null;
    }
  }
}
