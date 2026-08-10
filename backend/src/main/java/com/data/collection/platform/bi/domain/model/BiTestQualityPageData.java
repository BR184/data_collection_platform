package com.data.collection.platform.bi.domain.model;

import java.math.BigDecimal;
import java.util.List;

/** CAT 单元测试或集成测试页面的整体、模块、功能三层数据。 */
public record BiTestQualityPageData(
    Attainment overall,
    List<ModuleAttainment> modules,
    List<FunctionAttainment> functions) {
  public BiTestQualityPageData {
    modules = modules == null ? List.of() : List.copyOf(modules);
    functions = functions == null ? List.of() : List.copyOf(functions);
  }

  /** 测试质量达成基础值。 */
  public record Attainment(
      CountSummary counts,
      BigDecimal passRate,
      BigDecimal targetRate,
      Boolean achieved) {}

  /** 上游可证实的计数摘要；计数单位禁止在适配过程中互换。 */
  public record CountSummary(
      CountUnit unit,
      long attainedCount,
      long totalCount) {
    public CountSummary {
      if (unit == null || attainedCount < 0 || totalCount < 0 || attainedCount > totalCount) {
        throw new IllegalArgumentException("测试达成计数无效");
      }
    }
  }

  /** 当前页面支持的计数单位。 */
  public enum CountUnit {
    TEST_CASE,
    FUNCTION
  }

  /** 模块级测试达成数据。 */
  public record ModuleAttainment(
      String moduleId,
      String moduleName,
      Attainment attainment) {}

  /** 功能级测试达成数据。 */
  public record FunctionAttainment(
      String moduleId,
      String functionId,
      String functionName,
      Attainment attainment) {}
}
