package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.CustomerIssueDelayClosureOrchestrator;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

/**
 * 运行终态事件的监听顺序是正确性前提：收敛评估方只有在事实发布登记已提交后才能读取收敛判据。
 * 顺序一旦反转（或注解丢失），延期写回会在目标尚未登记时读到"未发布目标为 0"而提前推进。
 */
class SyncRunCompletionListenerOrderTest {

  @Test
  void factPublicationListenerMustRunBeforeFactConvergenceConsumers() throws Exception {
    Method publication =
        SyncRunFactRefreshListener.class.getMethod(
            "onSyncRunCompleted", SyncRunCompletionEvent.class);
    Method convergence =
        CustomerIssueDelayClosureOrchestrator.class.getMethod(
            "onRunTerminal", SyncRunCompletionEvent.class);

    Order publicationOrder = publication.getAnnotation(Order.class);
    Order convergenceOrder = convergence.getAnnotation(Order.class);

    assertThat(publicationOrder).isNotNull();
    assertThat(convergenceOrder).isNotNull();
    assertThat(publicationOrder.value())
        .isEqualTo(SyncRunCompletionListenerOrder.FACT_PUBLICATION);
    assertThat(convergenceOrder.value())
        .isEqualTo(SyncRunCompletionListenerOrder.FACT_CONVERGENCE_CONSUMER);
    assertThat(convergenceOrder.value()).isGreaterThan(publicationOrder.value());
  }
}
