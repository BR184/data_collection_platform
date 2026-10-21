package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import java.time.Instant;

/**
 * 旁路生产者（不消费变化头的写入者）开启并准备自有候选的输入；完整发布仍须等待必需投影。
 *
 * <p>与 {@link CandidateOpenRequest} 的区别是本请求由发布编排服务补全域基线：调用方只声明写入归属的域、
 * 来源身份与<b>该来源自己的完整代际证明</b>。来源证明必须由生产者按自身控制面解析，不能用墙上时钟或
 * 其它来源的状态代替；缺少证明时不允许开启候选。
 *
 * @param domainKey 目标一致性域
 * @param sourceSystem 写入行的来源系统（业务键的一部分，如 GITLAB / LEGACY_PLATFORM）
 * @param sourceSealJson 该来源的完整代际与定义证明（JSON，非空）
 * @param coveredChangeVersion 本候选覆盖的变化水位上界；无变化头协议的生产者为 0
 * @param producerRunId 生产运行标识（审计，可空）
 * @param producerTaskId 生产任务标识（审计，可空）
 * @param dataAsOf 该完整输入对应的数据截止时刻
 * @param fullDataset 是否读取该自有来源的完整数据集并开启新代际；局部定向集合必须为 false
 */
public record BypassPublicationRequest(
    ReadPublicationDomainKey domainKey,
    String sourceSystem,
    String sourceSealJson,
    long coveredChangeVersion,
    String producerRunId,
    Long producerTaskId,
    Instant dataAsOf,
    boolean fullDataset) {

  public BypassPublicationRequest {
    if (domainKey == null) {
      throw new IllegalArgumentException("旁路发布必须声明目标域");
    }
    if (sourceSystem == null || sourceSystem.isBlank()) {
      throw new IllegalArgumentException("旁路发布必须声明来源系统");
    }
    if (sourceSealJson == null || sourceSealJson.isBlank()) {
      throw new IllegalArgumentException("旁路发布必须携带来源完整代际证明，不能留空由发布层猜测");
    }
    if (coveredChangeVersion < 0L) {
      throw new IllegalArgumentException("旁路发布覆盖水位不得为负");
    }
    if (dataAsOf == null) {
      throw new IllegalArgumentException("旁路发布必须声明数据截止时刻");
    }
  }
}
