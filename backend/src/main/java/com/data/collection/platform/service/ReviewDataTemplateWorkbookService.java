package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataTemplateWorkbookService {
  private static final String LEGACY_TEMPLATE_PATH = "review/文档评审.xls";

  public byte[] buildTemplateWorkbook() {
    ClassPathResource resource = new ClassPathResource(LEGACY_TEMPLATE_PATH);
    try (var inputStream = resource.getInputStream()) {
      return inputStream.readAllBytes();
    } catch (IOException error) {
      throw new BizException("评审模板文件读取失败");
    }
  }
}
