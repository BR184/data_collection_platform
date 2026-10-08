package com.data.collection.platform.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在单一数据库事务中构建并发布一组事实变更。 */
@Service
public class FactPublicationTransaction {
  private final FactTaskExecutionGuard executionGuard;

  public FactPublicationTransaction(FactTaskExecutionGuard executionGuard) {
    this.executionGuard = executionGuard;
  }

  /**
   * 执行事实变更；提交前其他连接继续读取上一已提交版本，失败时全部回滚。
   *
   * <p>归属事实任务时，本事务先验证父运行与任务两层执行权并持有共享行锁至提交，因此撤销执行权与
   * 在途批次不会交错；手工同步构建没有任务上下文，不作此校验。
   *
   * @param action 事实构建动作
   * @param <T> 构建结果类型
   * @return 构建结果
   */
  @Transactional
  public <T> T execute(Supplier<T> action) {
    executionGuard.requireCurrentTaskAuthorization();
    return action.get();
  }
}
