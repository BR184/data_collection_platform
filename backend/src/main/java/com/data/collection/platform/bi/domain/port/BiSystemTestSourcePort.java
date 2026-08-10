package com.data.collection.platform.bi.domain.port;

import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.source.BiSystemTestSource;

/** 系统测试有效缺陷事实和轮次目录的只读端口。 */
public interface BiSystemTestSourcePort {
  /** 按稳定产品版本冻结并读取系统测试数据。 */
  BiSystemTestSource load(BiProductVersionScope scope);
}
