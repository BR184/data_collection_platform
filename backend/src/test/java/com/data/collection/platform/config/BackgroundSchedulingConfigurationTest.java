package com.data.collection.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class BackgroundSchedulingConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BackgroundSchedulingConfiguration.class));

  @Test
  void backgroundJobsEnabledByDefaultRegistersScheduler() {
    contextRunner.run(context -> assertThat(context)
        .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
  }

  @Test
  void backgroundJobsDisabledDoesNotRegisterScheduler() {
    contextRunner
        .withPropertyValues("platform.background-jobs.enabled=false")
        .run(context -> assertThat(context)
            .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
  }
}
