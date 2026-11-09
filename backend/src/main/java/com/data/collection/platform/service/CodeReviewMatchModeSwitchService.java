package com.data.collection.platform.service;

import org.springframework.stereotype.Service;

@Service
//兼容模式-MatchMode：老平台交接期读源开关唯一门面。
//兼容模式-MatchMode：退役时须先验收正式生产链与所有消费者，再删除此门面及兼容分支；切正式读源不等于停止旧同步或旧源刷新。
public class CodeReviewMatchModeSwitchService {
  private final CodeReviewMatchModeConfigService configService;

  public CodeReviewMatchModeSwitchService(CodeReviewMatchModeConfigService configService) {
    this.configService = configService;
  }

  /** 兼容总开关；不能据此推断代码走查和评审各自最终读源，也不表示已完成数据交接。 */
  public boolean isEnabled() {
    return configService.isMatchModeEnabled();
  }

  //兼容模式-MatchMode：评审数据在兼容态读取正式记录与尚未由平台接管的老平台快照；正式态只读正式表。
  public boolean isReviewDataCompatibilityReadEnabled() {
    return configService.isReviewDataCompatibilityReadEnabled();
  }

  /** 仅当总开关开启且代码走查读源为 compatibility 时返回 true，与评审读源独立。 */
  public boolean isCodeReviewCompatibilityReadEnabled() {
    return configService.isCodeReviewCompatibilityReadEnabled();
  }
}
