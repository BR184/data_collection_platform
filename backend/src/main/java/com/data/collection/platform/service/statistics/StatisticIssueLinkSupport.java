package com.data.collection.platform.service.statistics;

import com.data.collection.platform.service.GitlabIssueLinkService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StatisticIssueLinkSupport {
  private final GitlabIssueLinkService issueLinkService;

  public StatisticIssueLinkSupport(GitlabIssueLinkService issueLinkService) {
    this.issueLinkService = issueLinkService;
  }

  public void putIssueFields(
      Map<String, Object> record, Integer issueIid, Long projectId, String projectName) {
    String issueUrl = issueLinkService.issueUrl(projectId, issueIid);
    record.put("issueIid", issueIid);
    record.put("issueUrl", issueUrl);
    record.put("projectId", projectId);
    record.put("projectName", projectName);
    record.put("iid", issueLinkValue(issueIid, issueUrl));
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
