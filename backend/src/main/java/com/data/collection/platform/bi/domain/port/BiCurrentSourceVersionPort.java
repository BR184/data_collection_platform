package com.data.collection.platform.bi.domain.port;

import com.data.collection.platform.bi.domain.source.BiProductVersionScope;

/** PNG 下载授权用于核对当前页面来源版本的窄端口。 */
public interface BiCurrentSourceVersionPort {
  /** 返回当前页面和产品版本的稳定来源版本。 */
  String current(String pageKey, BiProductVersionScope scope);
}
