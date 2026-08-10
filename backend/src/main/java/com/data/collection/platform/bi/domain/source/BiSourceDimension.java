package com.data.collection.platform.bi.domain.source;

import java.util.Objects;

/**
 * 单一冻结来源快照内可用于分组的维度值。
 *
 * <p>该值只表达来源字段在当前快照中的分组语义，不代表跨平台业务实体 ID。缺失来源值时使用
 * {@link #unknown(String)} 创建显式未知成员，避免丢弃事实或用显示文本伪造稳定 ID。</p>
 */
public record BiSourceDimension(String sourceValue, String displayName, boolean identified) {
  public BiSourceDimension {
    sourceValue = normalize(sourceValue);
    displayName = normalize(displayName);
    if (identified) {
      Objects.requireNonNull(sourceValue, "identified sourceValue");
      displayName = displayName == null ? sourceValue : displayName;
    } else {
      if (sourceValue != null) {
        throw new IllegalArgumentException("unknown source dimension cannot contain sourceValue");
      }
      Objects.requireNonNull(displayName, "unknown displayName");
    }
  }

  /** 从同一个物理字段创建已识别的分组值和展示名。 */
  public static BiSourceDimension identified(String value) {
    return new BiSourceDimension(value, value, true);
  }

  /** 从可空物理字段创建已识别值或显式未知成员。 */
  public static BiSourceDimension fromNullable(String value, String unknownDisplayName) {
    String normalized = normalize(value);
    return normalized == null ? unknown(unknownDisplayName) : identified(normalized);
  }

  /** 创建不与任何真实来源值碰撞的显式未知成员。 */
  public static BiSourceDimension unknown(String displayName) {
    return new BiSourceDimension(null, displayName, false);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
