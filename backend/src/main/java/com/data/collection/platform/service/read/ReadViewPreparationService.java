package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadModelRules;
import com.data.collection.platform.entity.read.ReadView;
import com.data.collection.platform.service.BusinessDateProvider;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 在一个固定提交视图内确定来源模式、完整闭包和发布点；读取数据前结束准备事务。 */
@Service
public class ReadViewPreparationService {
  private final ReadPageCatalog pages;
  private final PublishedReadService reads;
  private final BusinessDateProvider dates;
  private final TransactionTemplate transactions;

  public ReadViewPreparationService(ReadPageCatalog pages, PublishedReadService reads, BusinessDateProvider dates,
      PlatformTransactionManager transactionManager) {
    this.pages = pages;
    this.reads = reads;
    this.dates = dates;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /**
   * 模式与域指针在同一 RR 快照确定，准备完成即提交租约。
   * 发布或实时披露更新使 PostgreSQL 报 40001 时，按 {@link ReadSerializationRetry} 重建独立读事务；
   * 失败事务的租约与引用全部回滚，不重放同步命令，不休眠或吞掉其它异常。
   */
  public ReadView prepare(String subject, String session, String page, String scopeKey,
      Map<String, String> scope, UUID clientViewId) {
    return ReadSerializationRetry.execute(transactions, () -> {
      java.time.LocalDate businessDate = dates.businessDate();
      return reads.acquireView(new AcquireReadViewRequest(subject, session, page, scopeKey, businessDate,
          ReadModelRules.VERSION, pages.domains(page, scope, businessDate), clientViewId));
    });
  }
}
