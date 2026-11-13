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
  void freshSaveBypassesCursorAndHistoryStillAdvancesWithinBoundedBatch() {
    java.util.List<Long> sent = new java.util.ArrayList<>();
    ArrayDeque<Runnable> queue = new ArrayDeque<>();
    JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
      String sql = (String) invocation.getArgument(0);
      if (sql.startsWith("select exists")) return true;
      if (sql.contains("a.id >")) return java.util.stream.LongStream.rangeClosed(100, 119).boxed().toList();
      if (sql.contains("where source_config_id")) return java.util.List.of((Long) invocation.getArgument(5));
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    FormIssueLinkService service = org.mockito.Mockito.spy(new FormIssueLinkService(jdbc,
        mock(GitlabConfigService.class), new FormIssueLinkProperties(), mock(FormIssueSettingsService.class),
        mock(FormIssueCommentContentService.class), new ObjectMapper(), queue::add, mock(PlatformTransactionManager.class)));
    org.mockito.Mockito.doAnswer(invocation -> {
      long id = invocation.getArgument(0);
      sent.add(id);
      // 在历史请求运行中保存较小ID，下一次轮到新保存资源时必须处理，不能等游标回绕。
      if (id >= 100L) service.dispatchChangedAsync(event(1));
      return true;
    }).when(service).sendOne(org.mockito.ArgumentMatchers.anyLong());
    service.dispatchPendingAsync();
    queue.remove().run();
    assertThat(sent).hasSize(20);
    assertThat(sent.subList(0, 3)).containsExactly(100L, 1L, 101L);
    assertThat(sent.stream().filter(id -> id >= 100L).toList())
        .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(100, 109).boxed().toList());
    assertThat(queue).hasSize(1);
  }

  @Test
  void burstRetainsOnlyOneBatchOfDistinctHintsAndDatabaseFailureDoesNotSpin() {
    ArrayDeque<Runnable> queue = new ArrayDeque<>();
    java.util.List<Long> sent = new java.util.ArrayList<>();
    JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
      String sql = (String) invocation.getArgument(0);
      if (sql.startsWith("select exists")) return true;
      if (sql.contains("a.id >")) return java.util.List.of();
      if (sql.contains("where source_config_id")) return java.util.List.of((Long) invocation.getArgument(5));
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    FormIssueLinkService service = org.mockito.Mockito.spy(new FormIssueLinkService(jdbc,
        mock(GitlabConfigService.class), new FormIssueLinkProperties(), mock(FormIssueSettingsService.class),
        mock(FormIssueCommentContentService.class), new ObjectMapper(), queue::add, mock(PlatformTransactionManager.class)));
    org.mockito.Mockito.doAnswer(invocation -> { sent.add(invocation.getArgument(0)); return true; })
        .when(service).sendOne(org.mockito.ArgumentMatchers.anyLong());
    for (int i = 0; i < 1000; i++) { service.dispatchChangedAsync(event(i)); service.dispatchChangedAsync(event(i)); }
    assertThat(queue).hasSize(1);
    queue.remove().run();
    assertThat(sent).containsExactlyElementsOf(java.util.stream.LongStream.range(0, 20).boxed().toList());
    assertThat(queue).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenThrow(new IllegalStateException("数据库不可用"));
    service.dispatchChangedAsync(event(2));
    queue.remove().run();
    assertThat(queue).isEmpty();
  }

  private static com.data.collection.platform.service.FormIssueChangeBridge.FormWriteEvent event(long id) {
    return new com.data.collection.platform.service.FormIssueChangeBridge.FormWriteEvent(1, "default", 325, id,
        "issue", "issue_response");
  }

  @Test
  void rejectedSubmissionRetainsHintUntilNextCompensation() {
    ArrayDeque<Runnable> queue = new ArrayDeque<>();
    AtomicBoolean reject = new AtomicBoolean(true);
    java.util.List<Long> sent = new java.util.ArrayList<>();
    JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
      String sql = (String) invocation.getArgument(0);
      if (sql.startsWith("select exists")) return true;
      if (sql.contains("a.id >")) return java.util.List.of();
      if (sql.contains("where source_config_id")) return java.util.List.of(7L);
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    FormIssueLinkService service = org.mockito.Mockito.spy(new FormIssueLinkService(jdbc,
        mock(GitlabConfigService.class), new FormIssueLinkProperties(), mock(FormIssueSettingsService.class),
        mock(FormIssueCommentContentService.class), new ObjectMapper(), task -> {
          if (reject.getAndSet(false)) throw new java.util.concurrent.RejectedExecutionException("饱和");
          queue.add(task);
        }, mock(PlatformTransactionManager.class)));
    org.mockito.Mockito.doAnswer(invocation -> { sent.add(invocation.getArgument(0)); return true; })
        .when(service).sendOne(org.mockito.ArgumentMatchers.anyLong());
    service.dispatchChangedAsync(event(7));
    assertThat(queue).isEmpty();
    service.dispatchPendingAsync();
    queue.remove().run();
    assertThat(sent).containsExactly(7L);
    assertThat(queue).isEmpty();
  }

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
