package com.data.collection.platform.entity.read;

import java.time.Instant;
import java.util.UUID;

/**
 * 已发布事实的读上下文（单域）。
 *
 * <p>可见性由“代际 + 半开区间”决定：{@code epochId} 相等、{@code visible_from} 非空且不高于
 * {@code sequence}、且 {@code visible_to} 为空或不高于 {@code sequence}。旧完整版本可继续服务，
 * 数据新鲜度与可读性分离。
 *
 * @param sourceInstance 来源实例
 * @param table 目标事实表
 * @param epochId 事实代际
 * @param sequence 冻结的域内发布序号
 * @param publicationId 冻结的发布身份
 * @param dataAsOf 该版本的数据截止时刻
 */
public record PublishedReadContext(
    String sourceInstance,
    PublicationFactTable table,
    UUID epochId,
    long sequence,
    UUID publicationId,
    Instant dataAsOf)
    implements FactReadQueryContext, PublishedSourceContext {

  public PublishedReadContext {
    if (table == null || epochId == null || publicationId == null) {
      throw new IllegalArgumentException("已发布读上下文必须声明事实表、代际与发布身份");
    }
    if (sequence <= 0L) {
      throw new IllegalArgumentException("已发布读上下文必须带有效的域内序号");
    }
  }
}
