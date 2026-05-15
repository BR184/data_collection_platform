package com.data.collection.platform.service.statistics;

import com.data.collection.platform.config.GitlabMirrorProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StatisticIssueLinkSupport {
  private final String gitlabWebBaseUrl;

  public StatisticIssueLinkSupport(GitlabMirrorProperties gitlabMirrorProperties) {
    this.gitlabWebBaseUrl = gitlabMirrorProperties.getWebBaseUrl();
  }

  public void putIssueFields(
      Map<String, Object> record, Integer issueIid, Long projectId, String projectName) {
    String issueUrl = buildIssueUrl(issueIid);
    record.put("issueIid", issueIid);
    record.put("issueUrl", issueUrl);
    record.put("projectId", projectId);
    record.put("projectName", projectName);
    record.put("iid", issueLinkValue(issueIid, issueUrl));
  }

  private String buildIssueUrl(Integer issueIid) {
    if (!StringUtils.hasText(gitlabWebBaseUrl) || issueIid == null) {
      return null;
    }
    return gitlabWebBaseUrl.replaceAll("/+$", "") + "/-/issues/" + issueIid;
  }

  private Map<String, String> issueLinkValue(Integer issueIid, String issueUrl) {
    Map<String, String> value = new LinkedHashMap<>();
    value.put("label", issueIid == null ? "-" : String.valueOf(issueIid));
    if (StringUtils.hasText(issueUrl)) {
      value.put("href", issueUrl);
    }
    return value;
  }
}
