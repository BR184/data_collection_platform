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

/** 发送前输入快照的双读一致性：证据→回复→证据，期间推进即 PENDING。 */
class FormIssueBuildInputServiceTest {
  private static final String SOURCE = "default";
  private static final long PROJECT_ID = 325L;
  private static final long ISSUE_IID = 81001L;
  private static final FormIssueInputResolver.IssueRef REF =
      new FormIssueInputResolver.IssueRef(PROJECT_ID, ISSUE_IID);
  private static final FormHeadCaptureService.HeadKey HEAD_KEY =
      new FormHeadCaptureService.HeadKey(
          PROJECT_ID, ISSUE_IID, FormTemplateSpecCatalog.ISSUE_REPLY);

  private FormIssueInputResolver resolver;
  private FormHeadCaptureService captureService;
  private FormIssueBuildInputService service;

  @BeforeEach
  void setUp() {
    resolver = mock(FormIssueInputResolver.class);
    captureService = mock(FormHeadCaptureService.class);
    service = new FormIssueBuildInputService(resolver, captureService);
    when(resolver.loadScopes(SOURCE))
        .thenReturn(
            List.of(
                new FormIssueInputResolver.ScopeRow(
                    1L,
                    FormTemplateSpecCatalog.ISSUE_REPLY,
                    601L,
                    SOURCE,
                    "http://gitlab.test",
                    Set.of(PROJECT_ID),
                    "ACTIVE",
                    1L,
                    "set-hash")));
    when(resolver.loadAuthorities(eq(SOURCE), anyList(), anyList()))
        .thenReturn(
            Map.of(
                REF,
                Map.of(
                    FormTemplateSpecCatalog.ISSUE_REPLY,
                    new FormIssueInputResolver.AuthorityRow(
                        601L,
                        SOURCE,
                        "http://gitlab.test",
                        PROJECT_ID,
                        "issue",
                        ISSUE_IID,
                        FormTemplateSpecCatalog.ISSUE_REPLY,
                        1L,
                        "FORM",
                        1L,
                        FormTemplateSpecCatalog.ISSUE_REPLY,
                        SOURCE,
                        "http://gitlab.test",
                        "[" + PROJECT_ID + "]",
                        "ACTIVE"))));
    when(resolver.resolveAll(eq(SOURCE), anyList()))
        .thenReturn(
            new FormIssueInputResolver.Resolution(
                Map.of(REF, IssueBusinessInput.commentOnly()), Set.of()));
  }

  @Test
  void evidenceAdvancingBetweenHeadAndReplyReadReturnsPending() {
    when(captureService.loadHeads(eq(SOURCE), eq("issue"), any()))
        .thenReturn(Map.of(HEAD_KEY, head(1L)), Map.of(HEAD_KEY, head(2L)));

    FormIssueBuildInputService.Snapshot snapshot =
        service.current(SOURCE, PROJECT_ID, ISSUE_IID);

    assertThat(snapshot.status()).isEqualTo(FormIssueBuildInputService.Snapshot.Status.PENDING);
    assertThat(snapshot.hash()).isNull();
    assertThat(snapshot.reply()).isNull();
    verify(resolver).resolveAll(eq(SOURCE), anyList());
    verify(captureService, times(2)).loadHeads(eq(SOURCE), eq("issue"), any());
  }

  @Test
  void stableEvidenceReturnsHashAndReplyFromSameWindow() {
    when(captureService.loadHeads(eq(SOURCE), eq("issue"), any()))
        .thenReturn(Map.of(HEAD_KEY, head(3L)))
        .thenReturn(Map.of(HEAD_KEY, head(3L)));

    FormIssueBuildInputService.Snapshot snapshot =
        service.current(SOURCE, PROJECT_ID, ISSUE_IID);

    assertThat(snapshot.status()).isEqualTo(FormIssueBuildInputService.Snapshot.Status.OK);
    assertThat(snapshot.hash()).hasSize(64);
    assertThat(snapshot.reply()).isNotNull();
    verify(captureService, times(2)).loadHeads(eq(SOURCE), eq("issue"), any());
  }

  private static FormHeadCaptureService.HeadRow head(long latestChangeVersion) {
    return new FormHeadCaptureService.HeadRow(
        501L, 1L, latestChangeVersion, 0L, "PENDING", null, 0L, 1L, null, null, null, null,
        null, null);
  }
}
