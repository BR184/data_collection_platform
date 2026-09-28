package com.data.collection.platform.bi.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/** BI 下载的单一范围身份；产品版本和客户问题范围互斥。 */
public record BiDownloadScope(
    RangeType rangeType,
    Long productVersionId,
    String milestoneBusinessKey,
    LocalDate businessDate,
    MemberSelection customer,
    MemberSelection module,
    MemberSelection function) {

  public BiDownloadScope {
    Objects.requireNonNull(rangeType, "rangeType");
    if (rangeType == RangeType.PRODUCT_VERSION) {
      if (productVersionId == null || productVersionId <= 0
          || milestoneBusinessKey != null || businessDate != null
          || customer != null || module != null || function != null) {
        throw new IllegalArgumentException("产品版本下载范围只能包含正数产品版本 ID");
      }
    } else if (productVersionId != null
        || milestoneBusinessKey == null || milestoneBusinessKey.isBlank()
        || businessDate == null || customer == null || module == null || function == null) {
      throw new IllegalArgumentException("客户问题下载范围必须包含里程碑、业务日和三类成员条件");
    }
  }

  /** 构造现有六阶段页面使用的产品版本范围。 */
  public static BiDownloadScope productVersion(long productVersionId) {
    return new BiDownloadScope(
        RangeType.PRODUCT_VERSION, productVersionId, null, null, null, null, null);
  }

  /** 构造客户问题页面使用的类型化筛选范围。 */
  public static BiDownloadScope customerIssue(
      String milestoneBusinessKey,
      LocalDate businessDate,
      MemberSelection customer,
      MemberSelection module,
      MemberSelection function) {
    return new BiDownloadScope(
        RangeType.CUSTOMER_ISSUE, null, milestoneBusinessKey, businessDate,
        customer, module, function);
  }

  public enum RangeType {
    PRODUCT_VERSION,
    CUSTOMER_ISSUE
  }

  public enum SelectionKind {
    ALL,
    MISSING,
    VALUE
  }

  /** 一个维度只允许 ALL、MISSING 或携带非空真实值的 VALUE。 */
  public record MemberSelection(SelectionKind kind, String value) {
    public MemberSelection {
      Objects.requireNonNull(kind, "kind");
      boolean valid = switch (kind) {
        case ALL, MISSING -> value == null;
        case VALUE -> value != null && !value.isBlank();
      };
      if (!valid) {
        throw new IllegalArgumentException("成员筛选的类型和值不匹配");
      }
    }

    public static MemberSelection all() {
      return new MemberSelection(SelectionKind.ALL, null);
    }

    public static MemberSelection missing() {
      return new MemberSelection(SelectionKind.MISSING, null);
    }

    public static MemberSelection value(String value) {
      return new MemberSelection(SelectionKind.VALUE, value);
    }
  }
}
