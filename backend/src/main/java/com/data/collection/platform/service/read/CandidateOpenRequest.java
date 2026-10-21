package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import java.time.Instant;
import java.util.UUID;

/**
 * 开启候选的输入。
 *
 * <p>候选必须绑定基线发布身份、来源完整代际（{@code sourceSealJson}）与本次覆盖的变化水位上界，
 * 重启或重试不得拼接不同来源输入。
 *
 * @param domainKey 目标一致性域
 * @param epochId 目标代际；为 {@code null} 时沿用基线代际，首个版本自行生成
 * @param basePublicationId 基线发布身份；首个版本为空，且必须等于域当前发布指针
 * @param sourceSealJson 冻结的来源完整代际与定义证明（JSON）
 * @param coveredChangeVersion 本次候选覆盖的变化水位上界
 * @param definitionSnapshotId 引用的定义快照；无依赖定义时为空
 * @param producerRunId 生产运行标识（审计）
 * @param producerTaskId 生产任务标识（审计）
 * @param producerLeaseOwner 生产者租约持有者
 * @param dataAsOf 该完整输入对应的数据截止时刻
 */
public record CandidateOpenRequest(
    ReadPublicationDomainKey domainKey,
    UUID epochId,
    UUID basePublicationId,
    String sourceSealJson,
    long coveredChangeVersion,
    UUID definitionSnapshotId,
    String producerRunId,
    Long producerTaskId,
    String producerLeaseOwner,
    Instant dataAsOf) {

  public CandidateOpenRequest {
    if (domainKey == null) {
      throw new IllegalArgumentException("开启候选必须声明目标域");
    }
    if (sourceSealJson == null || sourceSealJson.isBlank()) {
      throw new IllegalArgumentException("开启候选必须冻结来源完整代际证明");
    }
    if (dataAsOf == null) {
      throw new IllegalArgumentException("开启候选必须声明数据截止时刻");
    }
    if (coveredChangeVersion < 0L) {
      throw new IllegalArgumentException("候选覆盖水位不得为负");
    }
  }
}
