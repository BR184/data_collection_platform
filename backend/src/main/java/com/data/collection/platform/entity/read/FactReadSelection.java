package com.data.collection.platform.entity.read;

import java.util.List;

/**
 * 查询显式持有的事实版本组合。公开入口由读取视图构造，后台预热由候选及固定基线构造；不从线程状态取版本。
 *
 * @param contexts 本次实际消费的已发布或候选上下文；域闭包由调用方核验，同一代际不得绑定两份身份
 */
public record FactReadSelection(List<FactReadQueryContext> contexts, java.time.LocalDate businessDate) implements ReadSelection {
  public FactReadSelection {
    contexts = List.copyOf(contexts);
    java.util.Objects.requireNonNull(businessDate, "读取版本必须固定业务日");
    if (contexts.isEmpty()) {
      throw new IllegalArgumentException("事实查询必须持有完整读取身份");
    }
    java.util.Set<String> identities = new java.util.HashSet<>();
    for (FactReadQueryContext context : contexts) {
      if (!identities.add(context.sourceInstance() + "/" + context.table() + "/" + context.epochId())) {
        throw new IllegalArgumentException("同一事实代际不能混用两个读取身份");
      }
    }
  }

  /** 本组合的稳定缓存身份；同一候选发布前后保持不变，不包含页面租约或会话信息。 */
  public String sourceVersion() {
    String identity = contexts.stream().map(context -> context.publicationId().toString()).sorted()
        .collect(java.util.stream.Collectors.joining("/")) + "/" + businessDate + "/" + ReadModelRules.VERSION;
    try {
      return "publication:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用", error);
    }
  }

  /** 预热以候选为主身份；公开读取按稳定来源/事实类型排序，供缓存归属与冻结定义使用。 */
  public java.util.UUID primaryPublicationId() {
    return contexts.stream().filter(FactReadQueryContext::candidate).findFirst()
        .orElseGet(() -> contexts.stream().min(java.util.Comparator
            .<FactReadQueryContext>comparingInt(context -> switch (context.table()) {
              case ISSUE -> 0; case MERGE_REQUEST -> 1; case INTEGRATION_TEST -> 2;
            }).thenComparing(FactReadQueryContext::sourceInstance).thenComparing(context -> context.publicationId().toString()))
            .orElseThrow()).publicationId();
  }

  /** 候选组合只供内部预热；公开视图组合全部为已发布身份。 */
  @Override
  public boolean candidate() { return contexts.stream().anyMatch(FactReadQueryContext::candidate); }
}
