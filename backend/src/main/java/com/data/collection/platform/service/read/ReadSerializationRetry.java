package com.data.collection.platform.service.read;

import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 40001 有界重建：并发续约、回收或发布更新同一行时，REPEATABLE READ 事务无法在自身快照上重新取锁。
 *
 * <p>每次重试都新建独立事务并重新取得提交快照，最多三次；不重放已提交的写，不休眠，不把业务 409/5xx 当作冲突。
 */
final class ReadSerializationRetry {
  private static final int MAX_ATTEMPTS = 3;

  private ReadSerializationRetry() {}

  /** 在独立事务中执行动作，仅对 PostgreSQL 序列化冲突重建事务，其它异常原样抛出。 */
  static <T> T execute(TransactionTemplate transactions, Supplier<T> action) {
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(status -> action.get());
      } catch (RuntimeException failure) {
        if (attempt >= MAX_ATTEMPTS || !isSerializationConflict(failure)) { throw failure; }
      }
    }
  }

  /** 遍历包装异常链寻找 PostgreSQL SQLState 40001；只有该状态代表可重建的快照冲突。 */
  static boolean isSerializationConflict(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof java.sql.SQLException sql && "40001".equals(sql.getSQLState())) { return true; }
    }
    return false;
  }
}
