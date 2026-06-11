package com.data.collection.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoDeprecatedTagGroupOrSegmentationRuntimeTest {
  private static final List<Pattern> FORBIDDEN_RUNTIME_MARKERS =
      List.of(
          Pattern.compile("\\btaggroup\\b"),
          Pattern.compile("\\btagselection\\b"),
          Pattern.compile("\\btagselections\\b"),
          Pattern.compile("\\bbusinesstaggroup\\b"),
          Pattern.compile("\\bsemantictaggroup\\b"),
          Pattern.compile("\\bsegmentfilterpreset\\b"),
          Pattern.compile("\\bsegmentdefinition\\b"),
          Pattern.compile("\\bsegmentcomputeservice\\b"),
          Pattern.compile("\\bsegmentcostestimator\\b"),
          Pattern.compile("\\bsegmentschemacompatibilitychecker\\b"),
          Pattern.compile("\\bsegmentmanagementcontroller\\b"),
          Pattern.compile("\\btag-groups\\b"),
          Pattern.compile("\\bbusiness-tag-groups\\b"),
          Pattern.compile("\\bsemantic-tag-groups\\b"),
          Pattern.compile("\\btag_group\\b"),
          Pattern.compile("\\btag_value\\b"),
          Pattern.compile("\\bbusiness_tag_group\\b"),
          Pattern.compile("\\bsemantic_tag_group\\b"),
          Pattern.compile("\\bsemantic_tag_value\\b"),
          Pattern.compile("\\bsegment_filter_preset\\b"),
          Pattern.compile("\\bsegment_definition\\b"),
          Pattern.compile("\\bsegment_compute_run\\b"),
          Pattern.compile("\\bsegment_member\\b"),
          Pattern.compile("\\bsegment_snapshot\\b"));

  @Test
  void mainCodeShouldNotContainDeprecatedTagGroupOrSegmentationRuntimeNames() throws IOException {
    Path mainRoot = Path.of("src", "main");
    try (Stream<Path> files = Files.walk(mainRoot)) {
      List<String> offenders =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".vue")
                  || path.toString().endsWith(".ts") || path.toString().endsWith(".sql"))
              .filter(NoDeprecatedTagGroupOrSegmentationRuntimeTest::containsForbiddenMarker)
              .map(path -> mainRoot.relativize(path).toString())
              .toList();

      assertThat(offenders).isEmpty();
    }
  }

  private static boolean containsForbiddenMarker(Path path) {
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      return FORBIDDEN_RUNTIME_MARKERS.stream().anyMatch(pattern -> pattern.matcher(content).find());
    } catch (IOException error) {
      throw new IllegalStateException("Failed to read " + path, error);
    }
  }
}
