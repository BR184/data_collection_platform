package com.data.collection.platform.entity.read;

import java.time.Instant;
import java.util.UUID;

/**
 * 持久完整发布身份。
 *
 * @param id 发布标识
 * @param domainId 所属一致性域
 * @param epochId 事实代际；增量在同一代际追加修订，全量开启新代际
 * @param sequenceNo 域内发布序号；公开读取以它作为可见性水位
 * @param basePublicationId 候选绑定的基线发布身份；首个版本为空
 * @param sourceSealJson 冻结的来源完整代际证明（JSON）
 * @param coveredChangeVersion 候选清单覆盖到的变化水位上界
 * @param definitionSnapshotId 引用的定义快照；无依赖定义时为空
 * @param state 发布状态
 * @param producerRunId 生产运行标识（审计）
 * @param producerTaskId 生产任务标识（审计）
 * @param producerLeaseOwner 生产租约持有者；发布前用于校验生产者仍有权提交
 * @param dataAsOf 该完整版本的数据截止时刻，不是请求时刻
 * @param publishedAt 发布时刻；未发布为空
 */
public record ReadPublication(
    UUID id,
    UUID domainId,
    UUID epochId,
    long sequenceNo,
    UUID basePublicationId,
    String sourceSealJson,
    long coveredChangeVersion,
    UUID definitionSnapshotId,
    ReadPublicationState state,
    String producerRunId,
    Long producerTaskId,
    String producerLeaseOwner,
    Instant dataAsOf,
    Instant publishedAt) {

  /** 是否为公开可读的完整版本。 */
  public boolean publiclyReadable() {
    return state != null && state.isPubliclyReadable();
  }

  /** 是否为占用域候选名额的活动候选。 */
  public boolean activeCandidate() {
    return state != null && state.isActiveCandidate();
  }
}
