package com.data.collection.platform.bi.domain.port;

import com.data.collection.platform.bi.domain.source.BiCodingSource;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;

/** 编码和人工代码走查已发布事实的只读端口。 */
public interface BiCodingSourcePort {
  /** 按稳定版本和编码页局部筛选读取同一来源版本的数据。 */
  BiCodingSource load(BiProductVersionScope scope, Query query);

  /** 编码页局部筛选；仓库只接受稳定 ID。 */
  record Query(
      BiCodingSource.Granularity granularity,
      Source source,
      String repositoryId) {}

  /** 已确认的编码来源筛选。 */
  enum Source {
    ALL,
    CC,
    DGM
  }
}
