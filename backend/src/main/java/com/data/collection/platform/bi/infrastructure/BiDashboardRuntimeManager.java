package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.application.BiDashboardRuntime;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.common.exception.BizException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 隔离 BI 延迟初始化和普通请求异常，不在启动阶段创建内部服务。 */
@Component
public class BiDashboardRuntimeManager {
  private static final Logger log = LoggerFactory.getLogger(BiDashboardRuntimeManager.class);

  private final BiDashboardRuntimeFactory factory;
  private volatile BiDashboardRuntime runtime;

  public BiDashboardRuntimeManager(BiDashboardRuntimeFactory factory) {
    this.factory = factory;
  }

  /** 返回延迟创建的 Runtime；初始化失败不会缓存半成品，下次请求可以重新尝试。 */
  public BiDashboardRuntime runtime() {
    BiDashboardRuntime current = runtime;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      current = runtime;
      if (current == null) {
        current = factory.create();
        runtime = current;
      }
      return current;
    }
  }

  /** 将 BI 页面初始化、查询和计算异常收敛为页面级 ERROR，不捕获 JVM 致命错误。 */
  public <T> BiPageResponse<T> page(
      String pageKey,
      Function<BiDashboardRuntime, BiPageResponse<T>> action) {
    try {
      return action.apply(runtime());
    } catch (BizException businessError) {
      throw businessError;
    } catch (BiSourceVersionChangedException changed) {
      log.info("bi_source_changed pageKey={} message={}", pageKey, changed.getMessage());
      return BiPageResponse.error(pageKey, changed.getMessage());
    } catch (RuntimeException error) {
      log.error("bi_page_failed pageKey={}", pageKey, error);
      return BiPageResponse.error(pageKey, "BI 页面数据加载失败，请稍后重试");
    }
  }
}
