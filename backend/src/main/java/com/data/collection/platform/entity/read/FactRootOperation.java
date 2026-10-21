package com.data.collection.platform.entity.read;

/** 候选根清单中的操作类型。 */
public enum FactRootOperation {
  /** 该根在本候选内产生了新修订。 */
  UPSERT,
  /** 该根在本候选内被删除，没有新修订，从基线排除后自然消失。 */
  DELETE
}
