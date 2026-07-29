package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SyncThreadBudgetResolver {
  public static final String MODE_FIXED = "FIXED";
  public static final String MODE_CPU_RATIO = "CPU_RATIO";
  public static final BigDecimal DEFAULT_FIXED_THREAD_VALUE = BigDecimal.valueOf(2);
  public static final BigDecimal DEFAULT_CPU_RATIO_VALUE = new BigDecimal("0.8");

  private final GitlabMirrorProperties properties;

  public SyncThreadBudgetResolver(GitlabMirrorProperties properties) {
    this.properties = properties;
  }

  public int resolve(GitlabSyncConfig config) {
    return resolve(config, Runtime.getRuntime().availableProcessors());
  }

  public int resolve(GitlabSyncConfig config, int availableProcessors) {
    int processorCount = Math.max(1, availableProcessors);
    int configuredMax = effectiveMaxThreads(config);
    BigDecimal configuredValue = effectiveValue(config);
    int requestedThreads =
        MODE_CPU_RATIO.equals(effectiveMode(config))
            ? floorToInt(configuredValue.multiply(BigDecimal.valueOf(processorCount)))
            : floorToInt(configuredValue);
    return Math.min(configuredMax, Math.max(1, requestedThreads));
  }

  /**
   * 解析 worker 与 DIRECT 连接池共享的唯一执行预算。
   *
   * @param config 数据源同步配置
   * @param availableProcessors 当前 JVM 可用处理器数量
   * @return 已完成上下限约束的执行预算
   */
  public SyncExecutionBudget resolveBudget(GitlabSyncConfig config, int availableProcessors) {
    int workers = resolve(config, availableProcessors);
    int controlReserve = Math.max(1, properties.getDirectPoolControlConnectionReserve());
    long acquireTimeoutMs = Math.max(1, properties.getDirectPoolAcquireTimeoutSeconds()) * 1000L;
    return new SyncExecutionBudget(
        workers,
        controlReserve,
        Math.addExact(workers, controlReserve),
        acquireTimeoutMs);
  }

  /**
   * 使用当前 JVM 处理器数量解析执行预算。
   *
   * @param config 数据源同步配置
   * @return 已解析执行预算
   */
  public SyncExecutionBudget resolveBudget(GitlabSyncConfig config) {
    return resolveBudget(config, Runtime.getRuntime().availableProcessors());
  }

  public String effectiveMode(GitlabSyncConfig config) {
    String mode = config == null ? null : config.getSyncThreadMode();
    if (mode == null || mode.isBlank()) {
      return MODE_FIXED;
    }
    String normalized = mode.trim().toUpperCase(Locale.ROOT);
    if (MODE_CPU_RATIO.equals(normalized)) {
      return MODE_CPU_RATIO;
    }
    return MODE_FIXED;
  }

  public BigDecimal effectiveValue(GitlabSyncConfig config) {
    BigDecimal value = config == null ? null : config.getSyncThreadValue();
    if (value != null) {
      return value;
    }
    return MODE_CPU_RATIO.equals(effectiveMode(config)) ? DEFAULT_CPU_RATIO_VALUE : DEFAULT_FIXED_THREAD_VALUE;
  }

  public int effectiveMaxThreads(GitlabSyncConfig config) {
    Integer configMax = config == null ? null : config.getMaxSyncThreads();
    int configuredMax = configMax == null ? properties.getMaxSyncThreads() : configMax;
    return Math.max(1, configuredMax);
  }

  private int floorToInt(BigDecimal value) {
    if (value == null) {
      return 0;
    }
    BigDecimal floored = value.setScale(0, RoundingMode.FLOOR);
    if (floored.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
      return Integer.MAX_VALUE;
    }
    return floored.intValue();
  }
}
