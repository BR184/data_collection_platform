package com.data.collection.platform.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 分别承载候选批次构建与完整版本发布事务；两者使用各自的执行权校验。 */
@Service
public class FactPublicationTransaction {
  private final FactTaskExecutionGuard executionGuard;

  public FactPublicationTransaction(FactTaskExecutionGuard executionGuard) {
    this.executionGuard = executionGuard;
  }

  /**
   * 执行候选事实批次；只提交候选修订，不切公开指针，失败时本批次全部回滚。
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

  /**
   * 候选统一发布事务：构建任务已经释放租约，调用动作负责校验候选父运行的当前执行权。
   * 不复用当前线程的构建任务授权；事实、指针、heads、generation 与栅栏必须在本事务共同提交。
   *
   * @param action 校验父运行并提交已准备候选的动作
   * @param <T> 发布结果类型
   * @return 发布结果；任意异常使整个发布事务回滚
   */
  @Transactional
  public <T> T commitCandidate(Supplier<T> action) {
    return action.get();
  }
}
