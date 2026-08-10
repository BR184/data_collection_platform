package com.data.collection.platform.bi.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将历史项目名称解析为已登记的产品版本业务键，不做显示文本模糊匹配。 */
public final class BiProductVersionMatcher {
  private static final Pattern VERSION_PATTERN = Pattern.compile("(20\\d{2})\\s*R\\s*(\\d+)");
  private static final Pattern FOLLOWING_RELEASE_PATTERN =
      Pattern.compile("(?:&|/|、|,)\\s*R\\s*(\\d+)");

  /** 判断项目名称是否明确包含目标版本，组合版本会展开为多个成员。 */
  public boolean matches(String projectName, String businessKey) {
    if (projectName == null || businessKey == null) {
      return false;
    }
    Set<String> projectVersions = extract(projectName);
    Set<String> targetVersions = extract(businessKey);
    return targetVersions.size() == 1 && projectVersions.contains(targetVersions.iterator().next());
  }

  private Set<String> extract(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toUpperCase(Locale.ROOT);
    Set<String> versions = new LinkedHashSet<>();
    Matcher matcher = VERSION_PATTERN.matcher(normalized);
    List<VersionAnchor> anchors = new ArrayList<>();
    while (matcher.find()) {
      anchors.add(new VersionAnchor(
          matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.start(), matcher.end()));
    }
    for (int index = 0; index < anchors.size(); index++) {
      VersionAnchor anchor = anchors.get(index);
      versions.add(versionKey(anchor.year(), anchor.release()));
      int segmentEnd = index + 1 < anchors.size() ? anchors.get(index + 1).start() : normalized.length();
      Matcher following = FOLLOWING_RELEASE_PATTERN.matcher(
          normalized.substring(anchor.end(), segmentEnd));
      while (following.find()) {
        versions.add(versionKey(anchor.year(), Integer.parseInt(following.group(1))));
      }
    }
    return versions;
  }

  private String versionKey(String year, int release) {
    return "CC" + year + "R" + release;
  }

  private record VersionAnchor(String year, int release, int start, int end) {}
}
