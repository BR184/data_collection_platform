package com.data.collection.platform.entity;

import java.util.Locale;

/**
 * 事实任务“继续”时的意图恢复模式。
 *
 * <p>{@link #ORIGINAL} 只恢复原任务实际持有的根；原任务已释放根且未保存根集合时明确拒绝，不静默扩大范围。
 * {@link #CURRENT_PENDING} 刷新该事实族当前待发布的根；{@link #FULL} 重建该事实族全量。
 */
public enum FactResumeMode {
  ORIGINAL,
  CURRENT_PENDING,
  FULL;

  public static FactResumeMode parse(String value) {
    if (value == null || value.isBlank()) {
      return ORIGINAL;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("不支持的事实继续模式：" + value);
    }
  }
}
