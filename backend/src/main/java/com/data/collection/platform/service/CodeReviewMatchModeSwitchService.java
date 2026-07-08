package com.data.collection.platform.service;

import org.springframework.stereotype.Service;

@Service
// 兼容模式 match mode：老平台交接期读源开关唯一门面。
// 后续彻底删除兼容模式时，优先删除本 Service 及调用它的 match mode 分支；正式表查询/导出逻辑不依赖本类。
public class CodeReviewMatchModeSwitchService {
  private final CodeReviewMatchModeConfigService configService;

  public CodeReviewMatchModeSwitchService(CodeReviewMatchModeConfigService configService) {
    this.configService = configService;
  }

  //兼容模式-MatchMode
  public boolean isEnabled() {
    return configService.isMatchModeEnabled();
  }

  //兼容模式-MatchMode
  public boolean isReviewDataCompatibilityReadEnabled() {
    return configService.isReviewDataCompatibilityReadEnabled();
  }

  //兼容模式-MatchMode
  public boolean isCodeReviewCompatibilityReadEnabled() {
    return configService.isCodeReviewCompatibilityReadEnabled();
  }
}
