package com.data.collection.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoFuzzyReviewJsonRelationRuntimeTest {
  private static final Pattern FUZZY_JSON_RELATION = Pattern.compile(
      "(?i)(problem_detail_ids|description_ids)[^\\n]{0,160}\\blike\\s+'%'\\s*\\|\\|");

  @Test
  void mainJavaCodeShouldUseExactReviewRelationReadModels() throws IOException {
    Path mainJavaRoot = Path.of("src", "main", "java");
    try (Stream<Path> files = Files.walk(mainJavaRoot)) {
      List<String> offenders = files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .filter(NoFuzzyReviewJsonRelationRuntimeTest::containsFuzzyJsonRelation)
          .map(path -> mainJavaRoot.relativize(path).toString())
          .toList();

      assertThat(offenders).isEmpty();
    }
  }

  private static boolean containsFuzzyJsonRelation(Path path) {
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8);
      return FUZZY_JSON_RELATION.matcher(content).find();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to read " + path, error);
    }
  }
}
