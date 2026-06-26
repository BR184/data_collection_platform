package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerIssueDelayLabelWritebackServiceTest {
  private final CustomerIssueDelayLabelWritebackService service =
      new CustomerIssueDelayLabelWritebackService(HttpClient.newHttpClient());

  @Test
  void shouldReplaceDelayLabelsLikeOldPlatform() {
    assertThat(service.desiredDelayLabels(
            List.of("模块：平台", "响应已延期", "解决已延期", "P1"),
            true,
            false))
        .containsExactly("模块：平台", "P1", "响应已延期");

    assertThat(service.desiredDelayLabels(
            List.of("模块：平台", "响应已延期", "解决已延期", "P1"),
            false,
            false))
        .containsExactly("模块：平台", "P1");
  }
}
