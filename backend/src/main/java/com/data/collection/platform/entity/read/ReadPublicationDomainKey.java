package com.data.collection.platform.entity.read;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;

/**
 * 一致性域的稳定键。
 *
 * <p>{@code factType} 是域类型标记：GitLab 事实族取 {@link FactType#name()}，其它生产者取自身定义的类型标记；
 * {@code tableKey} 供表级域（如数据库浏览器镜像表）区分具体表，非表级域为空串。
 */
public record ReadPublicationDomainKey(
    ReadPublicationProducerKind producerKind,
    String sourceInstance,
    String factType,
    String tableKey) {

  public ReadPublicationDomainKey {
    if (producerKind == null) {
      throw new IllegalArgumentException("读发布域必须声明生产者种类");
    }
    sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    factType = factType == null ? "" : factType.trim();
    tableKey = tableKey == null ? "" : tableKey.trim();
  }

  /** GitLab 事实族的域键：按来源实例与事实类型划分。 */
  public static ReadPublicationDomainKey gitlabFact(String sourceInstance, FactType factType) {
    if (factType == null) {
      throw new IllegalArgumentException("GitLab 事实域必须声明事实类型");
    }
    return new ReadPublicationDomainKey(
        ReadPublicationProducerKind.GITLAB_FACT, sourceInstance, factType.name(), "");
  }

  /** 数据库浏览器镜像表的表级域键。 */
  public static ReadPublicationDomainKey mirrorTable(String tableKey) {
    if (tableKey == null || tableKey.isBlank()) {
      throw new IllegalArgumentException("镜像表域必须声明表键");
    }
    return new ReadPublicationDomainKey(
        ReadPublicationProducerKind.MIRROR_TABLE, GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
        "DATASET", tableKey.trim());
  }
}
