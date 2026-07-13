package com.data.collection.platform.entity.analytics;

import org.springframework.http.MediaType;

public record AnalyticsDashboardExport(String filename, String contentType, byte[] content) {
  private static final String XLSX_MEDIA_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  public AnalyticsDashboardExport {
    content = content == null ? new byte[0] : content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  public static AnalyticsDashboardExport xlsx(String filename, byte[] content) {
    return new AnalyticsDashboardExport(filename, XLSX_MEDIA_TYPE, content);
  }

  public MediaType mediaType() {
    return MediaType.parseMediaType(contentType);
  }
}
