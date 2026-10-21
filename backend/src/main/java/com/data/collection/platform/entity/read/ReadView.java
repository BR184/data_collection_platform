package com.data.collection.platform.entity.read;

import java.util.UUID;

/**
 * 一次冻结的多域版本组合；页面主表、候选、明细与导出必须来自同一组合。
 *
 * @param viewId 读取上下文标识
 * @param subject 会话主体
 * @param pageKey 页面标识
 * @param scopeKey 规范化范围键
 * @param businessDate 固定业务日
 * @param ruleVersion 规则版本
 * @param expiresAt 租约到期时刻
 * @param rebindDeadline 安全换版时限；到点后需重新绑定新视图
 * @param domains 各域冻结到的已发布读上下文
 */
public record ReadView(
    UUID viewId,
    String subject,
    String pageKey,
    String scopeKey,
    java.time.LocalDate businessDate,
    String ruleVersion,
    java.time.Instant expiresAt,
    java.time.Instant rebindDeadline,
    java.util.Map<ReadPublicationDomainKey, PublishedSourceContext> domains) {

  public ReadView {
    domains = domains == null ? java.util.Map.of() : java.util.Map.copyOf(domains);
  }

  /** 取指定域的已发布读上下文；该视图未冻结此域时判为非法请求。 */
  public PublishedSourceContext requireDomain(ReadPublicationDomainKey key) {
    PublishedSourceContext context = domains.get(key);
    if (context == null) {
      throw new IllegalArgumentException("读取视图未冻结该域：" + key);
    }
    return context;
  }
}
