package com.data.collection.platform.common;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

public final class DownloadResponseHeaders {
  private static final String DEFAULT_FILENAME = "download.xlsx";

  private DownloadResponseHeaders() {
  }

  public static String attachment(String filename) {
    String normalized = normalize(filename);
    String encoded = UriUtils.encode(normalized, StandardCharsets.UTF_8);
    return "attachment; filename=\"" + asciiFallback(normalized) + "\"; filename*=UTF-8''" + encoded;
  }

  private static String normalize(String filename) {
    if (!StringUtils.hasText(filename)) {
      return DEFAULT_FILENAME;
    }
    String normalized = filename.replace('\r', ' ').replace('\n', ' ').trim();
    return StringUtils.hasText(normalized) ? normalized : DEFAULT_FILENAME;
  }

  private static String asciiFallback(String filename) {
    String extension = extensionOf(filename);
    String base = extension.isEmpty() ? filename : filename.substring(0, filename.length() - extension.length());
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < base.length(); index++) {
      char current = base.charAt(index);
      if (isSafeAscii(current)) {
        builder.append(current);
      }
    }
    String sanitizedBase = builder.toString().replaceAll("\\s+", " ").trim();
    sanitizedBase = sanitizedBase.replaceAll("[._ -]+$", "");
    if (!StringUtils.hasText(sanitizedBase)) {
      sanitizedBase = "download";
    }
    return sanitizedBase + extension;
  }

  private static boolean isSafeAscii(char value) {
    return value >= 0x20 && value <= 0x7E && value != '"' && value != '\\' && value != ';';
  }

  private static String extensionOf(String filename) {
    String lower = filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".xlsx")) {
      return ".xlsx";
    }
    if (lower.endsWith(".xls")) {
      return ".xls";
    }
    if (lower.endsWith(".csv")) {
      return ".csv";
    }
    return "";
  }
}
