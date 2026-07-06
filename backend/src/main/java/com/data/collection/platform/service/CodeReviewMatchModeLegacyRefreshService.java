package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CodeReviewMatchModeLegacyRefreshService {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private final CodeReviewMatchModeConfigService configService;
  private final CodeReviewMatchModeSyncService syncService;
  private final CodeReviewMatchModeSwitchService switchService;
  private final HttpClient httpClient;

  public CodeReviewMatchModeLegacyRefreshService(
      CodeReviewMatchModeConfigService configService,
      CodeReviewMatchModeSyncService syncService,
      CodeReviewMatchModeSwitchService switchService) {
    this.configService = configService;
    this.syncService = syncService;
    this.switchService = switchService;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  //兼容模式-MatchMode：短期复用老平台单条 MR 刷新接口，后续独立实现后整体删除。
  public int refreshOne(String source, Long mergeRequestIid) {
    if (!switchService.isEnabled()) {
      throw new BizException("兼容模式未开启，不能调用老平台单条刷新");
    }
    if (mergeRequestIid == null || mergeRequestIid <= 0) {
      throw new BizException("合并请求编号不能为空");
    }
    CodeReviewMatchModeConfig config = configService.loadConfig();
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(source == null ? "cc" : source);
    URI uri = legacyRefreshUri(config, normalizedSource, mergeRequestIid);
    try {
      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(REQUEST_TIMEOUT)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new BizException("老平台单条刷新失败，HTTP 状态码：" + response.statusCode());
      }
    } catch (IOException error) {
      throw new BizException("老平台单条刷新请求失败：" + rootMessage(error));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new BizException("老平台单条刷新请求被中断");
    }
    return syncService.syncSingleMergeRequest(normalizedSource, mergeRequestIid);
  }

  private URI legacyRefreshUri(
      CodeReviewMatchModeConfig config, String source, Long mergeRequestIid) {
    String baseUrl = configService.legacyApiBaseUrlForSource(config, source);
    if (!StringUtils.hasText(baseUrl)) {
      throw new BizException("老平台接口地址未配置");
    }
    String dataSource = "dgm".equalsIgnoreCase(source) ? "DGM" : "CrownCAD";
    String query = "issuableReference=" + encode(String.valueOf(mergeRequestIid))
        + "&dataSource=" + encode(dataSource);
    return URI.create(baseUrl + "/mergeRequest/updataByIssuableReferenceAndStatus?" + query);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String rootMessage(Throwable error) {
    Throwable cursor = error;
    while (cursor.getCause() != null) {
      cursor = cursor.getCause();
    }
    return cursor.getMessage() == null ? error.getClass().getSimpleName() : cursor.getMessage();
  }
}
