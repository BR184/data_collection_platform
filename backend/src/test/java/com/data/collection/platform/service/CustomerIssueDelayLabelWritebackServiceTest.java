package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerIssueDelayLabelWritebackServiceTest {
  private final CustomerIssueDelayLabelWritebackService service =
      new CustomerIssueDelayLabelWritebackService(HttpClient.newHttpClient(), true);

  @Test
  void shouldOnlyAddAndRemoveDelayLabels() {
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        service.delayLabelChange(
            List.of("模块：平台", "响应已延期", "解决已延期", "P1"),
            true,
            false);

    assertThat(change.addLabels()).isEmpty();
    assertThat(change.removeLabels()).containsExactly("解决已延期");
  }

  @Test
  void shouldDeduplicateDelayLabelChanges() {
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        service.delayLabelChange(
            List.of("响应已延期", "响应已延期", "模块：平台"),
            false,
            true);

    assertThat(change.addLabels()).containsExactly("解决已延期");
    assertThat(change.removeLabels()).containsExactly("响应已延期");
  }

  @Test
  void shouldSkipWhenDelayLabelsAlreadyMatchDesiredState() {
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        service.delayLabelChange(
            List.of("模块：平台", "响应已延期", "P1"),
            true,
            false);

    assertThat(change.isEmpty()).isTrue();
  }

  @Test
  void shouldUseCommaSeparatedGitlabLabelParameters() {
    String body =
        service.formBody(new CustomerIssueDelayLabelWritebackService.LabelChange(
            List.of("A", "B", "B"),
            List.of("C")));

    assertThat(body).isEqualTo("add_labels=A%2CB&remove_labels=C");
  }

  @Test
  void shouldRequireGlobalApiSwitchBeforeWritebackCanRun() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDelayLabelWritebackEnabled(true);
    config.setWebBaseUrl("https://gitlab.example.com");
    config.setApiToken("token");

    CustomerIssueDelayLabelWritebackService disabledService =
        new CustomerIssueDelayLabelWritebackService(HttpClient.newHttpClient(), false);

    assertThat(disabledService.isEnabled(config)).isFalse();
    assertThat(service.isEnabled(config)).isTrue();
  }
}
