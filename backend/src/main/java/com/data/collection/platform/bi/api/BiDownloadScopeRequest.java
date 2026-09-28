package com.data.collection.platform.bi.api;

import com.data.collection.platform.bi.domain.model.BiDownloadScope;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.MemberSelection;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.RangeType;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.SelectionKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/** 下载授权协议中的显式范围；不同范围的字段组合互斥并严格校验。 */
public record BiDownloadScopeRequest(
    @NotNull RangeType rangeType,
    @Positive Long productVersionId,
    String milestoneBusinessKey,
    LocalDate businessDate,
    String customerKind,
    String customer,
    String moduleKind,
    String module,
    String functionKind,
    String function) {

  @AssertTrue(message = "下载范围字段与范围类型不匹配")
  public boolean isRangeShapeValid() {
    if (rangeType == null) return false;
    if (rangeType == RangeType.PRODUCT_VERSION) {
      return productVersionId != null && productVersionId > 0
          && milestoneBusinessKey == null && businessDate == null
          && customerKind == null && customer == null
          && moduleKind == null && module == null
          && functionKind == null && function == null;
    }
    return productVersionId == null
        && milestoneBusinessKey != null && !milestoneBusinessKey.isBlank()
        && businessDate != null
        && validSelection(customerKind, customer)
        && validSelection(moduleKind, module)
        && validSelection(functionKind, function);
  }

  /** 将已通过 Bean Validation 的传输范围转成 BI 内部强类型范围。 */
  public BiDownloadScope toScope() {
    if (rangeType == RangeType.PRODUCT_VERSION) {
      return BiDownloadScope.productVersion(productVersionId);
    }
    return BiDownloadScope.customerIssue(
        milestoneBusinessKey, businessDate,
        selection(customerKind, customer),
        selection(moduleKind, module),
        selection(functionKind, function));
  }

  private static boolean validSelection(String kind, String value) {
    if (kind == null) return false;
    return switch (kind) {
      case "ALL", "MISSING" -> value == null;
      case "VALUE" -> value != null && !value.isBlank();
      default -> false;
    };
  }

  private static MemberSelection selection(String kind, String value) {
    return switch (SelectionKind.valueOf(kind)) {
      case ALL -> MemberSelection.all();
      case MISSING -> MemberSelection.missing();
      case VALUE -> MemberSelection.value(value);
    };
  }
}
