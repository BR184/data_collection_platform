package com.data.collection.platform.service.statistics;

import com.data.collection.platform.service.TextQuerySupport;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class CustomerIssueMilestoneOrdering {
  private static final Pattern CC_RELEASE_PATTERN =
      Pattern.compile("(?i)\\bCC\\s*(\\d{4})\\s*R\\s*(\\d+)\\b");
  private static final Pattern SPACED_CC_RELEASE_PATTERN =
      Pattern.compile("(?i)\\bCC\\s*\\d{4}\\s+R\\s*\\d+\\b");

  private CustomerIssueMilestoneOrdering() {}

  public static List<String> sortLatestFirst(Collection<String> milestones) {
    if (milestones == null || milestones.isEmpty()) {
      return List.of();
    }
    return milestones.stream()
        .map(TextQuerySupport::trimToNull)
        .filter(Objects::nonNull)
        .distinct()
        .sorted(CustomerIssueMilestoneOrdering::compareLatestFirst)
        .toList();
  }

  public static String latest(Collection<String> milestones) {
    return sortLatestFirst(milestones).stream().findFirst().orElse("");
  }

  private static int compareLatestFirst(String left, String right) {
    ReleaseVersion leftVersion = parse(left);
    ReleaseVersion rightVersion = parse(right);
    if (leftVersion.matched() && rightVersion.matched()) {
      int byYear = Integer.compare(rightVersion.year(), leftVersion.year());
      if (byYear != 0) {
        return byYear;
      }
      int byRelease = Integer.compare(rightVersion.release(), leftVersion.release());
      if (byRelease != 0) {
        return byRelease;
      }
      int bySpacedFormat = Boolean.compare(rightVersion.spacedFormat(), leftVersion.spacedFormat());
      if (bySpacedFormat != 0) {
        return bySpacedFormat;
      }
    } else if (leftVersion.matched() != rightVersion.matched()) {
      return leftVersion.matched() ? -1 : 1;
    }
    return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER).compare(right, left);
  }

  private static ReleaseVersion parse(String value) {
    if (!StringUtils.hasText(value)) {
      return ReleaseVersion.unmatched();
    }
    Matcher matcher = CC_RELEASE_PATTERN.matcher(value.trim());
    if (!matcher.find()) {
      return ReleaseVersion.unmatched();
    }
    return new ReleaseVersion(
        true,
        Integer.parseInt(matcher.group(1)),
        Integer.parseInt(matcher.group(2)),
        SPACED_CC_RELEASE_PATTERN.matcher(value.trim()).find());
  }

  private record ReleaseVersion(boolean matched, int year, int release, boolean spacedFormat) {
    private static ReleaseVersion unmatched() {
      return new ReleaseVersion(false, 0, 0, false);
    }
  }
}
