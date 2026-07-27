package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.util.Locale;

/** 议题范围目录匹配的事实字段维度。 */
public enum IssueScopeDimension {
  TESTING_PHASE("测试阶段"),
  MILESTONE("里程碑");

  private final String displayName;

  IssueScopeDimension(String displayName) {
    this.displayName = displayName;
  }

  /** 返回管理页面使用的中文维度名称。 */
  public String displayName() {
    return displayName;
  }

  /**
   * 解析并校验 API 或数据库中的维度文本。
   *
   * @param value 维度文本
   * @return 对应维度
   * @throws BizException 文本为空或不受支持时抛出
   */
  public static IssueScopeDimension parse(String value) {
    if (value == null || value.isBlank()) {
      throw new BizException("议题范围匹配维度不能为空");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new BizException("不支持的议题范围匹配维度：" + value);
    }
  }
}

