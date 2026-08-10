package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;

/** BI 顶部产品版本目录应用服务。 */
public final class BiVersionService {
  private final BiProductVersionPort versionPort;

  public BiVersionService(BiProductVersionPort versionPort) {
    this.versionPort = versionPort;
  }

  /** 返回平台议题阶段目录中的启用产品版本。 */
  public BiProductVersionCatalog catalog() {
    return versionPort.catalog();
  }
}
