package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 建立页面读取视图的输入。
 *
 * @param subject 会话主体
 * @param sessionId 会话标识
 * @param pageKey 页面标识
 * @param scopeKey 规范化范围键
 * @param businessDate 固定业务日
 * @param ruleVersion 规则版本
 * @param domains 该页面需要冻结的一致性域
 */
public record AcquireReadViewRequest(
    String subject,
    String sessionId,
    String pageKey,
    String scopeKey,
    LocalDate businessDate,
    String ruleVersion,
    Set<ReadPublicationDomainKey> domains,
    java.util.UUID clientViewId) {

  public AcquireReadViewRequest {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("读取视图必须绑定会话主体");
    }
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("读取视图必须绑定当前认证会话");
    }
    if (pageKey == null || pageKey.isBlank()) {
      throw new IllegalArgumentException("读取视图必须绑定页面标识");
    }
    if (scopeKey == null || scopeKey.isBlank() || ruleVersion == null || ruleVersion.isBlank() || businessDate == null) {
      throw new IllegalArgumentException("读取视图必须固定范围、业务日与规则");
    }
    if (domains == null || domains.isEmpty()) {
      throw new IllegalArgumentException("读取视图必须声明至少一个一致性域");
    }
    domains = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(domains));
    clientViewId = clientViewId == null ? java.util.UUID.randomUUID() : clientViewId;
  }
}
