package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.FormIssueLinkProperties;
import com.data.collection.platform.service.GitlabConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class FormIssueLinkDispatchTest {
  @Test
  void burstIsCoalescedAndChangesDuringWorkScheduleOneFollowUp() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ArrayDeque<Runnable> queue = new ArrayDeque<>();
    AtomicReference<FormIssueLinkService> reference = new AtomicReference<>();
    AtomicBoolean first = new AtomicBoolean(true);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenAnswer(invocation -> {
      if (first.getAndSet(false)) {
        for (int i = 0; i < 100; i++) reference.get().dispatchPendingAsync();
        assertThat(queue).isEmpty();
      }
      return false;
    });
    FormIssueLinkService service = new FormIssueLinkService(jdbc, mock(GitlabConfigService.class),
        new FormIssueLinkProperties(), mock(FormIssueSettingsService.class), mock(FormIssueCommentContentService.class),
        new ObjectMapper(), queue::add, mock(PlatformTransactionManager.class));
    reference.set(service);
    for (int i = 0; i < 100; i++) service.dispatchPendingAsync();
    assertThat(queue).hasSize(1);
    queue.remove().run();
    assertThat(queue).hasSize(1);
    queue.remove().run();
    assertThat(queue).isEmpty();
  }
}
