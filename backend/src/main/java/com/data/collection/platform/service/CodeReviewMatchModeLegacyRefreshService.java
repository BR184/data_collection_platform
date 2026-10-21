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

  /**
   * 兼容模式下调用老平台重新采集目标 MR，返回后立即替换本地兼容拆分行。
   *
   * @param source 来源实例；null 使用 CC，DGM 请求携带老平台要求的切源头
   * @param mergeRequestIid 来源项目内的正整数 MR 编号
   * @return 本次导入的兼容记录数，同一 MR 可有多条走查记录
   * @throws BizException 兼容模式关闭、编号无效或老接口请求失败
   */
  //兼容模式-MatchMode：短期复用老平台单条 MR 刷新接口，后续独立实现后整体删除。
  public int refreshOne(String source, Long mergeRequestIid) {
    if (!switchService.isEnabled()) {
      throw new BizException("兼容模式未开启，不能调用老平台单条刷新");
    }
    return refreshOneInternal(source, mergeRequestIid, false);
  }

  /**
   * 为已转正式的老平台数据刷新兼容中间记录，供调用方继续提升为正式事实。
   *
   * @param source 来源实例；null 使用 CC，DGM 与兼容入口共用切源协议
   * @param mergeRequestIid 来源项目内的正整数 MR 编号
   * @return 本次导入的兼容记录数；本方法不执行正式事实提升
   * @throws BizException 编号无效或老接口请求失败；不要求兼容模式开启
   */
  //兼容模式-MatchMode：老平台数据转正式后，正式事实行仍可复用老平台单条刷新。
  public int refreshOneForFormalImport(String source, Long mergeRequestIid) {
    return refreshOneInternal(source, mergeRequestIid, true);
  }

  private int refreshOneInternal(String source, Long mergeRequestIid, boolean formalImportRefresh) {
    if (mergeRequestIid == null || mergeRequestIid <= 0) {
      throw new BizException("合并请求编号不能为空");
    }
    CodeReviewMatchModeConfig config = configService.loadConfig();
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(source == null ? "cc" : source);
    URI uri = legacyRefreshUri(config, normalizedSource, mergeRequestIid);
    try {
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
          .timeout(REQUEST_TIMEOUT)
          .GET();
      //兼容模式-MatchMode：老平台拦截器只按请求头切 GitLab 和数据库，查询参数不负责切源。
      if ("dgm".equalsIgnoreCase(normalizedSource)) {
        requestBuilder.header("Data_Source", "DGM");
      }
      HttpRequest request = requestBuilder.build();
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
    return formalImportRefresh
        ? syncService.syncSingleMergeRequestForFormalImport(normalizedSource, mergeRequestIid)
        : syncService.syncSingleMergeRequest(normalizedSource, mergeRequestIid);
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
