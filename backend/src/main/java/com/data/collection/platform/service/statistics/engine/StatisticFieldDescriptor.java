package com.data.collection.platform.service.statistics.engine;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 统计看板筛选字段的唯一绑定描述：字段键到行取值器的映射。
 *
 * <p>每个看板通过字段注册表声明自己的字段集合，替代在匹配代码中硬编码 switch；
 * 操作符语义统一由 {@link StatisticFilterEngine} 实现，本类只负责"取值"。
 *
 * @param <R> 看板私有行类型（如各板的 IssueSource record）
 */
public record StatisticFieldDescriptor<R>(
    String fieldKey,
    StatisticFieldType type,
    Function<R, List<String>> valuesAccessor,
    Function<R, LocalDateTime> dateTimeAccessor,
    BiFunction<R, StatisticFilterCondition, Boolean> overridePredicate) {

  public StatisticFieldDescriptor {
    Objects.requireNonNull(fieldKey, "fieldKey 不能为空");
    Objects.requireNonNull(type, "type 不能为空");
    if (type == StatisticFieldType.MULTI_VALUE) {
      Objects.requireNonNull(valuesAccessor, "MULTI_VALUE 字段必须提供 valuesAccessor");
    }
    if (type == StatisticFieldType.DATETIME) {
      Objects.requireNonNull(dateTimeAccessor, "DATETIME 字段必须提供 dateTimeAccessor");
    }
  }

  /** 多值文本字段（单值字段用单元素列表表达）。 */
  public static <R> StatisticFieldDescriptor<R> multiValue(
      String fieldKey, Function<R, List<String>> valuesAccessor) {
    return new StatisticFieldDescriptor<>(fieldKey, StatisticFieldType.MULTI_VALUE, valuesAccessor, null, null);
  }

  /** 带覆盖谓词的多值文本字段：override 返回 null 时回落到通用操作符语义。 */
  public static <R> StatisticFieldDescriptor<R> multiValueWithOverride(
      String fieldKey,
      Function<R, List<String>> valuesAccessor,
      BiFunction<R, StatisticFilterCondition, Boolean> overridePredicate) {
    return new StatisticFieldDescriptor<>(
        fieldKey, StatisticFieldType.MULTI_VALUE, valuesAccessor, null, overridePredicate);
  }

  /** 日期时间字段。 */
  public static <R> StatisticFieldDescriptor<R> dateTime(
      String fieldKey, Function<R, LocalDateTime> dateTimeAccessor) {
    return new StatisticFieldDescriptor<>(fieldKey, StatisticFieldType.DATETIME, null, dateTimeAccessor, null);
  }
}
