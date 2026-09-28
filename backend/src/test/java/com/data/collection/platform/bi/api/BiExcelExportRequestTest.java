package com.data.collection.platform.bi.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.RangeType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 导出请求的入参契约校验。
 *
 * <p>该端点的表头与数据行完全由浏览器提交，服务端只做 OOXML 序列化。表头元素为 {@code null}
 * 会在列宽推导处抛 NPE，数据行本身为 {@code null} 会在行遍历处抛 NPE，两者都必须在校验层
 * 拒绝（返回 400 而不是 500）；单元格为 {@code null} 是合法输入（写成空串），不得一并拒绝。
 */
class BiExcelExportRequestTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void createValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  private static BiExcelExportRequest request(List<String> headers, List<List<Object>> rows) {
    return new BiExcelExportRequest(
        new BiDownloadScopeRequest(RangeType.PRODUCT_VERSION, 10L, null, null,
            null, null, null, null, null, null),
        "system-test",
        "system-test-assignee-workload",
        "developer-workload",
        "issue-version-3",
        "按指派人统计缺陷数",
        "统计各处理人员被指派的缺陷总数。",
        headers,
        rows);
  }

  private static String firstViolationPath(BiExcelExportRequest request) {
    Set<ConstraintViolation<BiExcelExportRequest>> violations = validator.validate(request);
    assertThat(violations).hasSize(1);
    return violations.iterator().next().getPropertyPath().toString();
  }

  @Test
  void rejectsNullHeaderElement() {
    assertThat(firstViolationPath(request(Arrays.asList("模块名称", null), List.of())))
        .startsWith("headers");
  }

  @Test
  void rejectsBlankHeaderElement() {
    assertThat(firstViolationPath(request(List.of("模块名称", "  "), List.of())))
        .startsWith("headers");
  }

  @Test
  void rejectsEmptyHeaders() {
    assertThat(firstViolationPath(request(List.of(), List.of()))).startsWith("headers");
  }

  @Test
  void rejectsNullRow() {
    assertThat(firstViolationPath(
            request(List.of("模块名称"), Collections.<List<Object>>singletonList(null))))
        .startsWith("rows");
  }

  @Test
  void acceptsNullCellValue() {
    assertThat(validator.validate(
            request(List.of("模块名称", "缺陷总数"), List.of(Arrays.<Object>asList(null, 10)))))
        .isEmpty();
  }

  @Test
  void acceptsNullRows() {
    assertThat(validator.validate(request(List.of("模块名称"), null))).isEmpty();
  }

  @Test
  void rejectsCustomerRangeWithIncompleteMemberSelection() {
    BiExcelExportRequest invalid = new BiExcelExportRequest(
        new BiDownloadScopeRequest(RangeType.CUSTOMER_ISSUE, null, "mile-1",
            java.time.LocalDate.of(2026, 9, 24), "VALUE", null, "ALL", null, "MISSING", null),
        "customer-issues", "customer-issue-daily-trend", "daily-defect-trend", "version-1",
        "缺陷日增与日修复", null, List.of("日期"), List.of());

    assertThat(validator.validate(invalid)).anySatisfy(violation ->
        assertThat(violation.getPropertyPath().toString()).contains("scope"));
  }
}
