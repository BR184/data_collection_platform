package com.data.collection.platform.service;

/**
 * 评审数据管理页"是否达标"的统一口径（业务规则 6.x 第 15 条）：
 * 设计说明书评审按缺陷密度 [0.30, 0.80] 判定，其余评审类型（含需求说明书评审）按 [0.20, 0.60] 判定。
 * 正式态读取、兼容态读取与排序 SQL 必须全部经由本类，禁止各处拷贝区间常量。
 */
final class ReviewDataReachStandardRule {
  private static final String DESIGN_REVIEW_TYPE = "设计说明书评审";
  private static final double DESIGN_MIN = 0.3D;
  private static final double DESIGN_MAX = 0.8D;
  private static final double DEFAULT_MIN = 0.2D;
  private static final double DEFAULT_MAX = 0.6D;

  private ReviewDataReachStandardRule() {}

  /**
   * 按评审类型判定评审缺陷密度是否达标。
   *
   * @param reviewType 记录的评审类型展示值；null 或未知类型按非设计口径判定
   * @param density 评审缺陷密度（个/页）；空值按 0 处理（视为未达标）
   */
  static boolean reached(String reviewType, double density) {
    return density >= minimum(reviewType) && density <= maximum(reviewType);
  }

  static double minimum(String reviewType) {
    return isDesign(reviewType) ? DESIGN_MIN : DEFAULT_MIN;
  }

  static double maximum(String reviewType) {
    return isDesign(reviewType) ? DESIGN_MAX : DEFAULT_MAX;
  }

  /**
   * 生成正式态"是否达标"排序用的 SQL 表达式，与 {@link #reached} 同口径；
   * 非设计类型（含空类型）走默认区间。
   *
   * @param reviewTypeColumn 评审类型列（含表别名）
   * @param densityColumn 缺陷密度列（含表别名）
   */
  static String orderExpression(String reviewTypeColumn, String densityColumn) {
    return "case when btrim(coalesce(" + reviewTypeColumn + ", '')) = '" + DESIGN_REVIEW_TYPE
        + "' then case when " + densityColumn + " >= " + DESIGN_MIN + " and " + densityColumn
        + " <= " + DESIGN_MAX + " then 1 else 0 end else case when " + densityColumn + " >= "
        + DEFAULT_MIN + " and " + densityColumn + " <= " + DEFAULT_MAX + " then 1 else 0 end end";
  }

  private static boolean isDesign(String reviewType) {
    return reviewType != null && DESIGN_REVIEW_TYPE.equals(reviewType.trim());
  }
}
