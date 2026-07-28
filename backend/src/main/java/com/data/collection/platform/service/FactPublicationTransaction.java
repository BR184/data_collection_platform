package com.data.collection.platform.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在单一数据库事务中构建并发布一组事实变更。 */
@Service
public class FactPublicationTransaction {

  /**
   * 执行事实变更；提交前其他连接继续读取上一已提交版本，失败时全部回滚。
   *
   * @param action 事实构建动作
   * @param <T> 构建结果类型
   * @return 构建结果
   */
  @Transactional
  public <T> T execute(Supplier<T> action) {
    return action.get();
  }
}
