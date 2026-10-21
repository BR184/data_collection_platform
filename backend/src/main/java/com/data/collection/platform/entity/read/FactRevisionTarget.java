package com.data.collection.platform.entity.read;

import java.util.UUID;

/**
 * 一次候选修订写入的目标身份。
 *
 * <p>生产者据它在候选代际内写入新修订：事实行的 {@code publication_epoch_id} 取 {@link #epochId()}，
 * {@code created_by_publication_id} 取 {@link #publicationId()}，父子关系绑定到该修订的物理 {@code id}。
 * 它是写入契约的显式组成部分，不通过线程上下文或数据库会话隐式替换。
 *
 * <p>候选是否站在已认证的完整基线之上由 {@code ReadPublicationService} 在开启候选时按域指针强制判定，
 * 不通过这个目标对象表达。
 *
 * @param publicationId 候选发布身份
 * @param epochId 候选所属事实代际
 * @param table 目标主事实表
 * @param sourceSystem 来源系统（业务键的一部分，如 GITLAB / LEGACY_PLATFORM）
 * @param sourceInstance 来源实例（业务键的一部分）
 * @param coveredChangeVersion 本候选覆盖的变化水位上界；删除证明按它登记捕获水位
 */
public record FactRevisionTarget(
    UUID publicationId,
    UUID epochId,
    PublicationFactTable table,
    String sourceSystem,
    String sourceInstance,
    long coveredChangeVersion) {

  public FactRevisionTarget {
    if (publicationId == null || epochId == null || table == null) {
      throw new IllegalArgumentException("候选修订目标必须声明发布身份、代际与事实表");
    }
    if (sourceSystem == null || sourceSystem.isBlank()) {
      throw new IllegalArgumentException("候选修订目标必须声明来源系统");
    }
    if (sourceInstance == null || sourceInstance.isBlank()) {
      throw new IllegalArgumentException("候选修订目标必须声明来源实例");
    }
    if (coveredChangeVersion < 0L) {
      throw new IllegalArgumentException("候选覆盖水位不得为负");
    }
  }

  /** 目标事实类型标记，与现有 {@code FactType} 契约一致。 */
  public String factType() {
    return table.name();
  }
}
