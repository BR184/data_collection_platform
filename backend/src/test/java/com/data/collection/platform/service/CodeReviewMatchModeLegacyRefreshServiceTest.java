package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 兼容刷新使用真实回环 HTTP 请求验证老平台切源协议，不连接外部服务。 */
class CodeReviewMatchModeLegacyRefreshServiceTest {
  private final CodeReviewMatchModeConfigService configService = mock(CodeReviewMatchModeConfigService.class);
  private final CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
  private final CodeReviewMatchModeSyncService syncService = mock(CodeReviewMatchModeSyncService.class);
  private final AtomicReference<LegacyRequest> receivedRequest = new AtomicReference<>();
  private HttpServer server;
  private CodeReviewMatchModeConfig config;
  private CodeReviewMatchModeLegacyRefreshService service;
  private volatile int responseStatus;

  @BeforeEach
  void setUp() throws Exception {
    responseStatus = 200;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mergeRequest/updataByIssuableReferenceAndStatus", exchange -> {
      receivedRequest.set(new LegacyRequest(
          exchange.getRequestMethod(),
          exchange.getRequestURI().getRawQuery(),
          exchange.getRequestHeaders().getFirst("Data_Source")));
      exchange.sendResponseHeaders(responseStatus, -1);
      exchange.close();
    });
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    config = new CodeReviewMatchModeConfig(
        "", "", "", "", "spider_crowncad_data", baseUrl, baseUrl,
        List.of(), 100, "", "", List.of(), "", "", "compatibility", "compatibility", false);
    service = new CodeReviewMatchModeLegacyRefreshService(configService, syncService, switchService);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @ParameterizedTest
  @CsvSource({"dgm,false,DGM", "dgm,true,DGM", "cc,false,CrownCAD", "cc,true,CrownCAD"})
  void test_source_refresh_sends_matching_legacy_routing_contract(
      String source, boolean formalImport, String legacySource) {
    stubRequest(source);
    if (formalImport) {
      when(syncService.syncSingleMergeRequestForFormalImport(source, 22273L)).thenReturn(2);
    } else {
      when(syncService.syncSingleMergeRequest(source, 22273L)).thenReturn(2);
    }

    int rows = formalImport
        ? service.refreshOneForFormalImport(source, 22273L)
        : service.refreshOne(source, 22273L);

    assertThat(rows).isEqualTo(2);
    assertThat(receivedRequest.get()).isEqualTo(new LegacyRequest(
        "GET", "issuableReference=22273&dataSource=" + legacySource,
        "dgm".equals(source) ? "DGM" : null));
  }

  @Test
  void test_missing_source_refresh_uses_default_cc_contract() {
    stubRequest("cc");
    when(syncService.syncSingleMergeRequest("cc", 22273L)).thenReturn(1);

    assertThat(service.refreshOne(null, 22273L)).isEqualTo(1);
    assertThat(receivedRequest.get()).isEqualTo(new LegacyRequest(
        "GET", "issuableReference=22273&dataSource=CrownCAD", null));
  }

  @Test
  void test_legacy_http_failure_refresh_rejects_without_importing_data() {
    stubRequest("dgm");
    responseStatus = 503;

    assertThatThrownBy(() -> service.refreshOne("dgm", 22273L))
        .isInstanceOf(BizException.class).hasMessageContaining("503");
    verifyNoInteractions(syncService);
  }

  @Test
  void test_disabled_match_mode_refresh_rejects_without_external_request() {
    when(switchService.isEnabled()).thenReturn(false);

    assertThatThrownBy(() -> service.refreshOne("dgm", 22273L))
        .isInstanceOf(BizException.class).hasMessageContaining("兼容模式未开启");
    assertThat(receivedRequest.get()).isNull();
    verifyNoInteractions(syncService);
  }

  @Test
  void test_missing_legacy_url_refresh_rejects_without_external_request() {
    when(switchService.isEnabled()).thenReturn(true);
    when(configService.loadConfig()).thenReturn(config);
    when(configService.legacyApiBaseUrlForSource(config, "cc")).thenReturn("");

    assertThatThrownBy(() -> service.refreshOne("cc", 22273L))
        .isInstanceOf(BizException.class).hasMessageContaining("老平台接口地址未配置");
    assertThat(receivedRequest.get()).isNull();
    verifyNoInteractions(syncService);
  }

  @Test
  void test_invalid_iid_refresh_rejects_without_external_request() {
    when(switchService.isEnabled()).thenReturn(true);

    assertThatThrownBy(() -> service.refreshOne("dgm", 0L))
        .isInstanceOf(BizException.class).hasMessageContaining("合并请求编号不能为空");
    assertThat(receivedRequest.get()).isNull();
    verifyNoInteractions(syncService);
  }

  private void stubRequest(String source) {
    when(switchService.isEnabled()).thenReturn(true);
    when(configService.loadConfig()).thenReturn(config);
    when(configService.legacyApiBaseUrlForSource(config, source)).thenReturn(config.legacyApiBaseUrl());
  }

  private record LegacyRequest(String method, String query, String sourceHeader) {}
}
