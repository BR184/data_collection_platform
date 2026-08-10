package com.data.collection.platform.bi.domain.model;

import java.util.List;

/** BI 可用产品版本和默认选择。 */
public record BiProductVersionCatalog(
    Long defaultVersionId,
    List<BiProductVersionOption> versions) {
  public BiProductVersionCatalog {
    versions = versions == null ? List.of() : List.copyOf(versions);
  }
}
