package com.data.collection.platform.entity.read;

import java.util.UUID;

/**
 * 未发布候选的读上下文（单域），仅供背景预热器显式使用。
 *
 * <p>读取语义为“基线可见事实排除候选触及根，再并入候选新修订”：删除根只出现在候选清单中、没有新修订，
 * 因此从基线排除后自然消失。全量候选开启新代际时，基线不可见，只读本候选内容。
 *
 * @param sourceInstance 来源实例
 * @param table 目标事实表
 * @param epochId 候选所属代际
 * @param publicationId 候选发布身份
 * @param basePublicationId 候选绑定的基线发布身份；首个版本为空
 * @param baseEpochId 基线代际；首个版本为空
 * @param baseSequence 基线域内序号；首个版本为 0
 * @param coveredChangeVersion 候选覆盖到的变化水位上界
 */
public record CandidateReadContext(
    String sourceInstance,
    PublicationFactTable table,
    UUID epochId,
    UUID publicationId,
    UUID basePublicationId,
    UUID baseEpochId,
    long baseSequence,
    long coveredChangeVersion)
    implements FactReadQueryContext {

  public CandidateReadContext {
    if (table == null || epochId == null || publicationId == null) {
      throw new IllegalArgumentException("候选读上下文必须声明事实表、代际与候选身份");
    }
    if (baseSequence < 0L) {
      throw new IllegalArgumentException("候选基线序号不得为负");
    }
  }

  /** 是否为全量候选：开启新代际时基线不可见，只能读本候选内容。 */
  public boolean opensNewEpoch() {
    return basePublicationId == null || !epochId.equals(baseEpochId);
  }
}
