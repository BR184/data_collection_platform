package com.data.collection.platform.service;

import org.springframework.stereotype.Service;

@Service
//兼容模式-MatchMode：老平台交接期读源开关唯一门面。
//兼容模式-MatchMode：后续彻底删除兼容模式时，优先删除本 Service 及调用它的分支；正式表查询/导出逻辑不依赖本类。
public class CodeReviewMatchModeSwitchService {
  private final CodeReviewMatchModeConfigService configService;
  private final Boolean reviewCompatibility;
  private final Boolean codeCompatibility;

  @org.springframework.beans.factory.annotation.Autowired
  public CodeReviewMatchModeSwitchService(CodeReviewMatchModeConfigService configService) {
    this.configService = configService;
    this.reviewCompatibility = null;
    this.codeCompatibility = null;
  }

  private CodeReviewMatchModeSwitchService(CodeReviewMatchModeConfigService configService, boolean review, boolean code) {
    this.configService = configService;
    this.reviewCompatibility = review;
    this.codeCompatibility = code;
  }

  /** 页面使用固定设置；生产/编辑工厂继续当前权威模式，不缓存控制状态。 */
  public CodeReviewMatchModeSwitchService forRead(com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    return new CodeReviewMatchModeSwitchService(configService,
        configService.compatibilityReadEnabled(selection, true), configService.compatibilityReadEnabled(selection, false));
  }

  //兼容模式-MatchMode
  public boolean isEnabled() {
    return configService.isMatchModeEnabled();
  }

  //兼容模式-MatchMode：评审数据在兼容态读取正式记录与尚未由平台接管的老平台快照；正式态只读正式表。
  public boolean isReviewDataCompatibilityReadEnabled() {
    return reviewCompatibility == null ? configService.isReviewDataCompatibilityReadEnabled() : reviewCompatibility;
  }

  //兼容模式-MatchMode
  public boolean isCodeReviewCompatibilityReadEnabled() {
    return codeCompatibility == null ? configService.isCodeReviewCompatibilityReadEnabled() : codeCompatibility;
  }
}
