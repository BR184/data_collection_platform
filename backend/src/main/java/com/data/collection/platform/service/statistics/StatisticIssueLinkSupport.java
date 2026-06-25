package com.data.collection.platform.service.statistics;

import com.data.collection.platform.service.GitlabResourceLinkService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StatisticIssueLinkSupport {
  public static final String LABEL_COLORS_FIELD = "_labelColors";

  private static final Pattern HEX_COLOR_PATTERN =
      Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

  private final GitlabResourceLinkService issueLinkService;
  private final JdbcTemplate jdbcTemplate;

  public StatisticIssueLinkSupport(GitlabResourceLinkService issueLinkService, JdbcTemplate jdbcTemplate) {
    this.issueLinkService = issueLinkService;
    this.jdbcTemplate = jdbcTemplate;
  }

  public void putIssueFields(
      Map<String, Object> record, Integer issueIid, Long projectId, String projectName) {
    putIssueFields(record, null, issueIid, projectId, projectName);
  }

  public void putIssueFields(
      Map<String, Object> record, String sourceInstance, Integer issueIid, Long projectId, String projectName) {
    String issueUrl = issueLinkService.issueUrl(sourceInstance, projectId, issueIid);
    record.put("issueIid", issueIid);
    record.put("issueUrl", issueUrl);
    record.put("sourceInstance", sourceInstance);
    record.put("projectId", projectId);
    record.put("projectName", projectName);
    record.put("iid", issueLinkValue(issueIid, issueUrl));
  }

  public void putLabelColors(Map<String, Object> record, String sourceInstance, Long issueId, List<String> labels) {
    if (record == null || issueId == null || labels == null || labels.isEmpty()) {
      return;
    }
    Map<String, String> colors = labelColors(sourceInstance, issueId);
    if (!colors.isEmpty()) {
      record.put(LABEL_COLORS_FIELD, colors);
    }
  }

  public void putIssueMetadata(
      Map<String, Object> record,
      String sourceInstance,
      Integer issueIid,
      Long projectId,
      String projectName,
      Long issueId,
      List<String> labels) {
    putIssueFields(record, sourceInstance, issueIid, projectId, projectName);
    putLabelColors(record, sourceInstance, issueId, labels);
  }

  private Map<String, String> issueLinkValue(Integer issueIid, String issueUrl) {
    Map<String, String> value = new LinkedHashMap<>();
    value.put("label", issueIid == null ? "-" : "#" + issueIid);
    if (StringUtils.hasText(issueUrl)) {
      value.put("href", issueUrl);
    }
    return value;
  }

  private Map<String, String> labelColors(String sourceInstance, Long issueId) {
    String labelLinksTable =
        GitlabSourceInstanceSupport.rewriteMirrorTableReferences("ods_gitlab_label_links", sourceInstance);
    String labelsTable =
        GitlabSourceInstanceSupport.rewriteMirrorTableReferences("ods_gitlab_labels", sourceInstance);
    try {
      return jdbcTemplate.query(
          """
          select nullif(btrim(l.title), '') as title,
                 nullif(btrim(l.color), '') as color
            from %s ll
            join %s l
              on l.id = ll.label_id
             and coalesce(l.mirror_deleted, false) = false
           where coalesce(ll.mirror_deleted, false) = false
             and ll.target_type = 'Issue'
             and ll.target_id = ?
             and l.title is not null
             and l.title <> ''
             and l.color is not null
             and l.color <> ''
           order by lower(l.title)
          """.formatted(labelLinksTable, labelsTable),
          ps -> ps.setLong(1, issueId),
          rs -> {
            Map<String, String> colors = new LinkedHashMap<>();
            while (rs.next()) {
              String title = rs.getString("title");
              String color = normalizeColor(rs.getString("color"));
              if (StringUtils.hasText(title) && color != null) {
                putColorAlias(colors, title, color);
              }
            }
            return colors;
          });
    } catch (DataAccessException error) {
      return Map.of();
    }
  }

  private String normalizeColor(String color) {
    if (!StringUtils.hasText(color)) {
      return null;
    }
    String normalized = color.trim();
    return HEX_COLOR_PATTERN.matcher(normalized).matches() ? normalized : null;
  }

  private void putColorAlias(Map<String, String> colors, String label, String color) {
    String normalized = label.trim();
    colors.putIfAbsent(normalized, color);
    int separatorIndex = legacySeparatorIndex(normalized);
    if (separatorIndex > 0 && separatorIndex + 1 < normalized.length()) {
      String value = normalized.substring(separatorIndex + 1).trim();
      if (StringUtils.hasText(value)) {
        colors.putIfAbsent(value, color);
      }
    }
  }

  private int legacySeparatorIndex(String label) {
    int best = -1;
    for (char separator : new char[] {'：', ':', '-'}) {
      int index = label.indexOf(separator);
      if (index > 0 && (best < 0 || index < best)) {
        best = index;
      }
    }
    return best;
  }
}
