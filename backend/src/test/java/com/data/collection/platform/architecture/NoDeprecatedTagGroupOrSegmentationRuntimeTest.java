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

class NoAbandonedLabelGroupingImplementationTest {
  private static final List<Pattern> FORBIDDEN_RUNTIME_MARKERS =
      List.of(
          Pattern.compile("\\btaggroup\\b"),
          Pattern.compile("\\btagselection\\b"),
          Pattern.compile("\\btagselections\\b"),
          Pattern.compile("(?<!semantic-)\\btag-groups\\b"),
          Pattern.compile("(?<!semantic_)\\btag_group\\b"),
          Pattern.compile("(?<!semantic_)\\btag_value\\b"));

  @Test
  void mainCodeShouldNotContainAbandonedLabelGroupingNames() throws IOException {
    Path mainRoot = Path.of("src", "main");
    try (Stream<Path> files = Files.walk(mainRoot)) {
      List<String> offenders =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".vue")
                  || path.toString().endsWith(".ts") || path.toString().endsWith(".sql"))
              .filter(path -> !path.toString().contains("db\\migration")
                  && !path.toString().contains("db/migration"))
              .filter(NoAbandonedLabelGroupingImplementationTest::containsForbiddenMarker)
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
