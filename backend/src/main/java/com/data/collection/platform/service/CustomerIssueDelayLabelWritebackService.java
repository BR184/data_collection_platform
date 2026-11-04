package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDelayLabelWritebackService {
  static final String RESPONSE_DELAY_LABEL = "响应已延期";
  static final String RESOLVE_DELAY_LABEL = "解决已延期";
  static final String DEFERRAL_STATUS_LABEL = "状态：申请延期";
  static final String TECHNICAL_BLOCKER_LABEL = "技术卡点";
  static final String RESOURCE_BLOCKER_LABEL = "资源卡点";
  static final String DATA_ANOMALY_LABEL = "数据异常";
  private static final Map<String, String> DEFERRAL_REASON_LABELS =
      Map.of(
          "TECHNICAL_BLOCKER", TECHNICAL_BLOCKER_LABEL,
          "RESOURCE_BLOCKER", RESOURCE_BLOCKER_LABEL,
          "DATA_ANOMALY", DATA_ANOMALY_LABEL);
  private static final Set<String> SLA_LABELS = Set.of(RESPONSE_DELAY_LABEL, RESOLVE_DELAY_LABEL);
  private static final Set<String> DEFERRAL_LABELS =
      Set.of(
          DEFERRAL_STATUS_LABEL,
          TECHNICAL_BLOCKER_LABEL,
          RESOURCE_BLOCKER_LABEL,
          DATA_ANOMALY_LABEL);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();

  /**
   * 合并两种延期标签与本期四类延期原因标签的差异；两个分支各只增删自己拥有的标签。
   *
   * @param currentLabels 当前来源标签
   * @param responseDelayed 响应延期判定
   * @param resolveDelayed 解决延期判定
   * @param deferralManaged 议题是否出现过新版回复（{@code false} 时不增删本期四标签）
   * @param deferralReason 冻结延期原因枚举键；非申请延期、取消或旧版回复时为空
   * @return 合并后的增删集合
   */
  LabelChange labelChange(
      List<String> currentLabels,
      boolean responseDelayed,
      boolean resolveDelayed,
      boolean deferralManaged,
      String deferralReason) {
    LinkedHashSet<String> current = normalized(currentLabels);
    LinkedHashSet<String> addLabels = new LinkedHashSet<>();
    LinkedHashSet<String> removeLabels = new LinkedHashSet<>();
    collectLabelDiff(
        current,
        SLA_LABELS,
        desiredSlaLabels(responseDelayed, resolveDelayed),
        addLabels,
        removeLabels);
    if (deferralManaged) {
      collectLabelDiff(
          current, DEFERRAL_LABELS, desiredDeferralLabels(deferralReason), addLabels, removeLabels);
    }
    return new LabelChange(List.copyOf(addLabels), List.copyOf(removeLabels));
  }

  private LinkedHashSet<String> normalized(List<String> labels) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (String label : labels == null ? List.<String>of() : labels) {
      if (StringUtils.hasText(label)) {
        values.add(label.trim());
      }
    }
    return values;
  }

  private LinkedHashSet<String> desiredSlaLabels(boolean responseDelayed, boolean resolveDelayed) {
    LinkedHashSet<String> desired = new LinkedHashSet<>();
    if (responseDelayed) {
      desired.add(RESPONSE_DELAY_LABEL);
    }
    if (resolveDelayed) {
      desired.add(RESOLVE_DELAY_LABEL);
    }
    return desired;
  }

  private LinkedHashSet<String> desiredDeferralLabels(String deferralReason) {
    LinkedHashSet<String> desired = new LinkedHashSet<>();
    String reasonLabel = deferralReason == null ? null : DEFERRAL_REASON_LABELS.get(deferralReason);
    if (reasonLabel != null) {
      desired.add(DEFERRAL_STATUS_LABEL);
      desired.add(reasonLabel);
    }
    return desired;
  }

  /** 只在所属分支拥有的标签集合内比较：其他标签既不参与判断，也不会被本分支删除。 */
  private void collectLabelDiff(
      LinkedHashSet<String> current,
      Set<String> owned,
      LinkedHashSet<String> desired,
      LinkedHashSet<String> addLabels,
      LinkedHashSet<String> removeLabels) {
    LinkedHashSet<String> currentOwned = new LinkedHashSet<>();
    for (String label : current) {
      if (owned.contains(label)) {
        currentOwned.add(label);
      }
    }
    LinkedHashSet<String> add = new LinkedHashSet<>(desired);
    add.removeAll(currentOwned);
    LinkedHashSet<String> remove = new LinkedHashSet<>(currentOwned);
    remove.removeAll(desired);
    addLabels.addAll(add);
    removeLabels.addAll(remove);
  }

  /**
   * 该数据源当前是否允许写回延期标签。
   *
   * <p>唯一开关是镜像设置页的「延期标签写回」（{@code gitlab_sync_configs.delay_label_writeback_enabled}，
   * 默认关闭）；此外必须配好写回地址与令牌，否则即使开关打开也无处可写。关闭时仍会监控延期事实，
   * 但绝不调用 GitLab 写接口——worker 每个任务执行前都会重新判定本方法，关闭后已入队任务会被跳过。
   *
   * @param config 数据源配置，{@code null} 视为不可写
   * @return 开关打开且地址、令牌齐备时为 {@code true}
   */
  boolean isEnabled(GitlabSyncConfig config) {
    return config != null
        && Boolean.TRUE.equals(config.getDelayLabelWritebackEnabled())
        && StringUtils.hasText(config.getWebBaseUrl())
        && StringUtils.hasText(config.getApiToken());
  }

  int sendLabelUpdate(GitlabSyncConfig config, Long projectId, Long issueIid, LabelChange change)
      throws IOException, InterruptedException, GitlabLabelWritebackException {
    String baseUrl = stripTrailingSlash(config.getWebBaseUrl());
    URI uri = URI.create(baseUrl + "/api/v4/projects/" + projectId + "/issues/" + issueIid);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("PRIVATE-TOKEN", config.getApiToken())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .PUT(HttpRequest.BodyPublishers.ofString(formBody(change), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new GitlabLabelWritebackException(response.statusCode(), response.body());
    }
    return response.statusCode();
  }

  String formBody(LabelChange change) {
    StringBuilder body = new StringBuilder();
    appendLabelParameter(body, "add_labels", change == null ? List.of() : change.addLabels());
    appendLabelParameter(body, "remove_labels", change == null ? List.of() : change.removeLabels());
    return body.toString();
  }

  private void appendLabelParameter(StringBuilder body, String name, List<String> labels) {
    List<String> normalized =
        List.copyOf(new LinkedHashSet<>(
            (labels == null ? List.<String>of() : labels).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList()));
    if (normalized.isEmpty()) {
      return;
    }
    if (body.length() > 0) {
      body.append('&');
    }
    body.append(name).append('=');
    StringBuilder value = new StringBuilder();
    for (String label : normalized) {
      if (!StringUtils.hasText(label)) {
        continue;
      }
      if (value.length() > 0) {
        value.append(',');
      }
      value.append(label.trim());
    }
    body.append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
  }

  private String stripTrailingSlash(String value) {
    String result = value == null ? "" : value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  record LabelChange(List<String> addLabels, List<String> removeLabels) {
    LabelChange {
      addLabels = List.copyOf(new LinkedHashSet<>(addLabels == null ? List.of() : addLabels));
      removeLabels = List.copyOf(new LinkedHashSet<>(removeLabels == null ? List.of() : removeLabels));
    }

    boolean isEmpty() {
      return addLabels.isEmpty() && removeLabels.isEmpty();
    }
  }

  static class GitlabLabelWritebackException extends Exception {
    private final int httpStatus;

    GitlabLabelWritebackException(int httpStatus, String body) {
      super("GitLab label update failed: HTTP " + httpStatus + " " + (body == null ? "" : body));
      this.httpStatus = httpStatus;
    }

    int httpStatus() {
      return httpStatus;
    }
  }
}
