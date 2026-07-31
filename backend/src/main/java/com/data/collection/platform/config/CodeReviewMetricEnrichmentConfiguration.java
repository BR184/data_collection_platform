package com.data.collection.platform.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CodeReviewMetricEnrichmentConfiguration {

  @Bean(name = "codeReviewMetricCoordinator", destroyMethod = "shutdown")
  ExecutorService codeReviewMetricCoordinator() {
    return Executors.newSingleThreadExecutor(threadFactory("code-review-metric-coordinator-"));
  }

  @Bean(name = "codeReviewMetricWorkers", destroyMethod = "shutdown")
  ExecutorService codeReviewMetricWorkers(GitlabMirrorProperties properties) {
    int concurrency = Math.max(1, Math.min(16, properties.getCodeReviewMetricConcurrency()));
    return Executors.newFixedThreadPool(concurrency, threadFactory("code-review-metric-worker-"));
  }

  private ThreadFactory threadFactory(String prefix) {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
