package com.data.collection.platform.entity.read;

/**
 * 事实读查询上下文：公开读取与候选读取的封闭强类型联合。
 *
 * <p>查询器只接受本接口，不允许用共享 ThreadLocal 或动态 Map 隐式替换读源；公开入口不得构造
 * {@link CandidateReadContext}。两种子类型描述的是同一套事实可见性规则的不同生命周期状态，
 * 不各自维护业务公式。
 */
public sealed interface FactReadQueryContext
    permits PublishedReadContext, CandidateReadContext {

  /** 来源实例。 */
  String sourceInstance();

  /** 目标事实表。 */
  PublicationFactTable table();

  /** 事实代际。 */
  java.util.UUID epochId();

  /** 固定的候选或已发布身份；用于缓存组合，不以最新 generation 替代。 */
  java.util.UUID publicationId();

  /** 是否读取未发布候选。 */
  default boolean candidate() {
    return this instanceof CandidateReadContext;
  }
}
