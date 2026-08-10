package com.data.collection.platform.bi.domain.port;

import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;

/** BI 使用的平台产品版本目录窄端口。 */
public interface BiProductVersionPort {
  /** 返回可选版本及默认版本。 */
  BiProductVersionCatalog catalog();

  /** 按稳定版本 ID 返回平台范围，不接受显示名替代。 */
  BiProductVersionScope requireScope(long productVersionId);
}
