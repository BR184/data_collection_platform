package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.service.form.FormHeadCaptureService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 发送前输入快照的双读一致性：范围→权威→head 全程读取两次，任一侧推进即 PENDING。 */
class FormIssueBuildInputServiceTest {
  private static final String SOURCE = "default";
  private static final long PROJECT_ID = 325L;
  private static final long ISSUE_IID = 81001L;
  private static final long REPLY_SCOPE_ID = 1L;
  private static final long RESPONSE_SCOPE_ID = 2L;
  private static final long CONFIG_ID = 601L;
  private static final FormIssueInputResolver.IssueRef REF =
      new FormIssueInputResolver.IssueRef(PROJECT_ID, ISSUE_IID);
  private static final FormHeadCaptureService.HeadKey REPLY_HEAD =
      new FormHeadCaptureService.HeadKey(
          PROJECT_ID, ISSUE_IID, FormTemplateSpecCatalog.ISSUE_REPLY);
  private static final FormHeadCaptureService.HeadKey RESPONSE_HEAD =
      new FormHeadCaptureService.HeadKey(
          PROJECT_ID, ISSUE_IID, FormTemplateSpecCatalog.ISSUE_RESPONSE);

  private FormIssueInputResolver resolver;
  private FormHeadCaptureService captureService;
  private FormIssueBuildInputService service;

  @BeforeEach
  void setUp() {
    resolver = mock(FormIssueInputResolver.class);
    captureService = mock(FormHeadCaptureService.class);
    service = new FormIssueBuildInputService(resolver, captureService);
    when(resolver.loadScopes(SOURCE)).thenReturn(scopes("set-hash"));
    when(resolver.loadAuthorities(eq(SOURCE), anyList(), any()))
        .thenReturn(Map.of(REF, Map.of(FormTemplateSpecCatalog.ISSUE_REPLY, replyAuthority())));
    when(resolver.resolveAll(eq(SOURCE), anyList()))
        .thenReturn(
            new FormIssueInputResolver.Resolution(
                Map.of(REF, IssueBusinessInput.commentOnly()), Set.of()));
  }

  @Test
  void evidenceAdvancingBetweenHeadAndReplyReadReturnsPending() {
    when(captureService.loadHeads(eq(SOURCE), eq("issue"), any()))
        .thenReturn(Map.of(REPLY_HEAD, head(1L)), Map.of(REPLY_HEAD, head(2L)));

    FormIssueBuildInputService.Snapshot snapshot =
        service.current(SOURCE, PROJECT_ID, ISSUE_IID);

    assertThat(snapshot.status()).isEqualTo(FormIssueBuildInputService.Snapshot.Status.PENDING);
    assertThat(snapshot.hash()).isNull();
    assertThat(snapshot.reply()).isNull();
    verify(resolver).resolveAll(eq(SOURCE), anyList());
    verify(captureService, times(2)).loadHeads(eq(SOURCE), eq("issue"), any());
  }

  @Test
  void replySideAuthorityAddedDuringReadReturnsPending() {
    when(captureService.loadHeads(eq(SOURCE), eq("issue"), any()))
        .thenReturn(
            Map.of(REPLY_HEAD, head(3L)),
            Map.of(REPLY_HEAD, head(3L), RESPONSE_HEAD, head(2L)));
    when(resolver.loadAuthorities(eq(SOURCE), anyList(), any()))
        .thenReturn(Map.of(REF, Map.of(FormTemplateSpecCatalog.ISSUE_REPLY, replyAuthority())))
        .thenReturn(
            Map.of(
                REF,
                Map.of(
                    FormTemplateSpecCatalog.ISSUE_REPLY,
                    replyAuthority(),
                    FormTemplateSpecCatalog.ISSUE_RESPONSE,
                    responseAuthority())));

    FormIssueBuildInputService.Snapshot snapshot =
        service.current(SOURCE, PROJECT_ID, ISSUE_IID);

    assertThat(snapshot.status())
        .as("读取期间新增回复侧权威必须改变签名并等待收敛")
        .isEqualTo(FormIssueBuildInputService.Snapshot.Status.PENDING);
    assertThat(snapshot.hash()).isNull();
    assertThat(snapshot.reply()).isNull();
    verify(resolver, times(2)).loadScopes(SOURCE);
    verify(resolver, times(2)).loadAuthorities(eq(SOURCE), anyList(), any());
  }

  @Test
  void scopeSetChangeDuringReadReturnsPending() {
    when(resolver.loadScopes(SOURCE))
        .thenReturn(scopes("set-hash"))
        .thenReturn(scopes("set-hash-2"));
    when(captureService.loadHeads(eq(SOURCE), eq("issue"), any()))
        .thenReturn(Map.of(REPLY_HEAD, head(3L)));

    FormIssueBuildInputService.Snapshot snapshot =
        service.current(SOURCE, PROJECT_ID, ISSUE_IID);

    assertThat(snapshot.status())
        .as("读取期间范围集合变化必须改变签名并等待收敛")
        .isEqualTo(FormIssueBuildInputService.Snapshot.Status.PENDING);
    assertThat(snapshot.hash()).isNull();
    verify(resolver, times(2)).loadScopes(SOURCE);
    verify(resolver, times(2)).loadAuthorities(eq(SOURCE), anyList(), any());
  }

  @Test
  void stableEvidenceReturnsHashAndReplyFromSameWindow() {
    when(captureService.loadHeads(eq(SOURCE), eq("issue"), any()))
        .thenReturn(Map.of(REPLY_HEAD, head(3L)))
        .thenReturn(Map.of(REPLY_HEAD, head(3L)));

    FormIssueBuildInputService.Snapshot snapshot =
        service.current(SOURCE, PROJECT_ID, ISSUE_IID);

    assertThat(snapshot.status()).isEqualTo(FormIssueBuildInputService.Snapshot.Status.OK);
    assertThat(snapshot.hash()).hasSize(64);
    assertThat(snapshot.reply()).isNotNull();
    verify(captureService, times(2)).loadHeads(eq(SOURCE), eq("issue"), any());
    verify(resolver, times(2)).loadScopes(SOURCE);
    verify(resolver, times(2)).loadAuthorities(eq(SOURCE), anyList(), any());
  }

  private static List<FormIssueInputResolver.ScopeRow> scopes(String replySourceSetHash) {
    return List.of(
        new FormIssueInputResolver.ScopeRow(
            REPLY_SCOPE_ID,
            FormTemplateSpecCatalog.ISSUE_REPLY,
            CONFIG_ID,
            SOURCE,
            "http://gitlab.test",
            Set.of(PROJECT_ID),
            "ACTIVE",
            1L,
            replySourceSetHash),
        new FormIssueInputResolver.ScopeRow(
            RESPONSE_SCOPE_ID,
            FormTemplateSpecCatalog.ISSUE_RESPONSE,
            CONFIG_ID,
            SOURCE,
            "http://gitlab.test",
            Set.of(PROJECT_ID),
            "ACTIVE",
            1L,
            "response-set-hash"));
  }

  private static FormIssueInputResolver.AuthorityRow replyAuthority() {
    return authority(REPLY_SCOPE_ID, FormTemplateSpecCatalog.ISSUE_REPLY);
  }

  private static FormIssueInputResolver.AuthorityRow responseAuthority() {
    return authority(RESPONSE_SCOPE_ID, FormTemplateSpecCatalog.ISSUE_RESPONSE);
  }

  private static FormIssueInputResolver.AuthorityRow authority(long scopeId, String templateKey) {
    return new FormIssueInputResolver.AuthorityRow(
        CONFIG_ID,
        SOURCE,
        "http://gitlab.test",
        PROJECT_ID,
        "issue",
        ISSUE_IID,
        templateKey,
        scopeId,
        "FORM",
        1L,
        templateKey,
        SOURCE,
        "http://gitlab.test",
        "[" + PROJECT_ID + "]",
        "ACTIVE");
  }

  private static FormHeadCaptureService.HeadRow head(long latestChangeVersion) {
    return new FormHeadCaptureService.HeadRow(
        501L, 1L, latestChangeVersion, 0L, "PENDING", null, 0L, 1L, null, null, null, null,
        null, null);
  }
}
