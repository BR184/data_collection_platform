package com.data.collection.platform.service;

import org.springframework.stereotype.Service;

@Service
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
