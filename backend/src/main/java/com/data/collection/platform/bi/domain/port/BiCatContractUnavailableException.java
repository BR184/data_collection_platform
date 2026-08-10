package com.data.collection.platform.bi.domain.port;

/** CAT 真实契约尚未冻结时使用的显式领域异常。 */
public final class BiCatContractUnavailableException extends RuntimeException {
  /** 使用已核实的阶段级缺口说明构造异常，禁止把不同阶段合并为同一笼统状态。 */
  public BiCatContractUnavailableException(String message) {
    super(message);
  }
}
