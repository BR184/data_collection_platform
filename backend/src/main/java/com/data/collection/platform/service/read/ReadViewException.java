package com.data.collection.platform.service.read;

import java.util.UUID;

/**
 * 读取视图的结构化失败信号。
 *
 * <p>它不是“没有数据”：{@code READ_VIEW_UNAVAILABLE} 表示该页面尚无任何完整产出；
 * {@code READ_VIEW_REBIND_REQUIRED} 表示当前视图已过期或已被回收，前端应保持当前画面并整体换版。
 */
public class ReadViewException extends RuntimeException {

  /** 结构化原因码。 */
  public enum Code {
    /** 该页面尚无完整产出，无法建立读取视图。 */
    READ_VIEW_UNAVAILABLE,
    /** 视图已过期或已被回收，需要重新绑定新视图。 */
    READ_VIEW_REBIND_REQUIRED
  }

  private final Code code;
  private final UUID viewId;

  private ReadViewException(Code code, UUID viewId, String message) {
    super(message);
    this.code = code;
    this.viewId = viewId;
  }

  /** 尚无完整产出。 */
  public static ReadViewException unavailable(String message) {
    return new ReadViewException(Code.READ_VIEW_UNAVAILABLE, null, message);
  }

  /** 需要换版。 */
  public static ReadViewException rebindRequired(UUID viewId, String message) {
    return new ReadViewException(Code.READ_VIEW_REBIND_REQUIRED, viewId, message);
  }

  /** 原因码。 */
  public Code code() {
    return code;
  }

  /** 涉及视图标识；不可用时为空。 */
  public UUID viewId() {
    return viewId;
  }
}
