package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupFileSupportTest {
  @TempDir Path tempDir;

  @Test
  void test_sanitizeLabel_nonAlnumCharactersCollapsedAndBlankFallsBackToDefault() {
    assertThat(BackupFileSupport.sanitizeLabel("qa-flex 20001/生产")).isEqualTo("qa-flex_20001___");
    assertThat(BackupFileSupport.sanitizeLabel("  ")).isEqualTo("default");
    assertThat(BackupFileSupport.sanitizeLabel(null)).isEqualTo("default");
  }

  @Test
  void test_fileNameFor_followsQaflexTimestampContract() {
    String name = BackupFileSupport.fileNameFor(
        "20001", LocalDateTime.of(2026, 9, 10, 3, 0, 5));

    assertThat(name).isEqualTo("qaflex_20001_20260910-030005.dump");
    assertThat(BackupFileSupport.dumpFilePattern("20001").matcher(name).matches()).isTrue();
  }

  @Test
  void test_dumpFilePattern_matchesOnlyOwnInstanceFiles() {
    Pattern pattern = BackupFileSupport.dumpFilePattern("20001");

    assertThat(pattern.matcher("qaflex_20001_20260910-030005.dump").matches()).isTrue();
    assertThat(pattern.matcher("qaflex_30001_20260910-030005.dump").matches()).isFalse();
    assertThat(pattern.matcher("qaflex_20001_backup.txt").matches()).isFalse();
    assertThat(pattern.matcher("important-notes.txt").matches()).isFalse();
  }

  @Test
  void test_namesBeyondRetention_deletesOldestBeyondLimit() {
    List<String> names = List.of(
        "qaflex_20001_20260901-030000.dump",
        "qaflex_20001_20260902-030000.dump",
        "qaflex_20001_20260903-030000.dump");

    List<String> toDelete =
        BackupFileSupport.namesBeyondRetention(names, BackupFileSupport.dumpFilePattern("20001"), 2);

    assertThat(toDelete).containsExactly("qaflex_20001_20260901-030000.dump");
  }

  @Test
  void test_namesBeyondRetention_neverTouchesForeignFiles() {
    List<String> names = List.of(
        "qaflex_20001_20260901-030000.dump",
        "qaflex_30001_20260901-040000.dump",
        "notes.txt",
        ".staging");

    List<String> toDelete =
        BackupFileSupport.namesBeyondRetention(names, BackupFileSupport.dumpFilePattern("20001"), 1);

    assertThat(toDelete).isEmpty();
  }

  @Test
  void test_resolveLocalSubdirectory_acceptsRelativeSegmentsAndRejectsTraversal() {
    assertThat(BackupFileSupport.resolveLocalSubdirectory(null)).isNull();
    assertThat(BackupFileSupport.resolveLocalSubdirectory("  ")).isNull();
    assertThat(BackupFileSupport.resolveLocalSubdirectory("backups/20001")).isEqualTo("backups/20001");

    assertThatThrownBy(() -> BackupFileSupport.resolveLocalSubdirectory("/absolute"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> BackupFileSupport.resolveLocalSubdirectory("../escape"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> BackupFileSupport.resolveLocalSubdirectory("a\\..\\b"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void test_sha256Hex_matchesKnownVector() throws Exception {
    Path file = tempDir.resolve("content.bin");
    Files.writeString(file, "");

    assertThat(BackupFileSupport.sha256Hex(file))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void test_listMatchedFileNames_listsOnlyPatternMatches() throws Exception {
    Files.writeString(tempDir.resolve("qaflex_20001_20260901-030000.dump"), "a");
    Files.writeString(tempDir.resolve("qaflex_30001_20260901-030000.dump"), "b");
    Files.createDirectory(tempDir.resolve("qaflex_20001_20260902-030000.dump"));

    List<String> names =
        BackupFileSupport.listMatchedFileNames(tempDir, BackupFileSupport.dumpFilePattern("20001"));

    assertThat(names).containsExactly("qaflex_20001_20260901-030000.dump");
  }
}
