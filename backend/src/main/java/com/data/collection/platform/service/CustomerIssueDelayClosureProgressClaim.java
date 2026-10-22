package com.data.collection.platform.service;

/**
 * 一次进度领取身份。
 *
 * <p>owner、令牌、处理修订与有效租约共同构成 fencing：旧处理者释放、推进或结算时只要任一
 * 不匹配，就表示所有权已经丢失，不能再改写进度。租约按数据库真实时钟校验，长事务内到期同样
 * 失效。
 *
 * @param progress 领取时的进度快照
 * @param owner 领取者标识
 * @param token 本次领取生成的令牌
 */
public record CustomerIssueDelayClosureProgressClaim(
    CustomerIssueDelayClosureProgress progress, String owner, String token) {

  public CustomerIssueDelayClosureProgressClaim withProgress(
      CustomerIssueDelayClosureProgress updated) {
    return new CustomerIssueDelayClosureProgressClaim(updated, owner, token);
  }
}
