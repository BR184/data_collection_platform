package com.data.collection.platform.entity.read;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 完整数据集及可选事实的固定组合，供兼容/正式记录和跨来源 BI 明确传递。 */
public record DatasetReadSelection(List<PublishedDatasetReadContext> datasets,
    FactReadSelection facts, java.time.LocalDate businessDate) implements ReadSelection {
  public DatasetReadSelection {
    datasets = List.copyOf(datasets);
    java.util.Objects.requireNonNull(businessDate, "读取版本必须固定业务日");
    if (facts != null && !businessDate.equals(facts.businessDate())) {
      throw new IllegalArgumentException("同一个页面不能混用两个业务日");
    }
    if (datasets.isEmpty() || facts != null && facts.candidate()) {
      throw new IllegalArgumentException("公开数据集必须持有完整身份，不能混入候选");
    }
  }

  /** 完整组合指纹，不用最新来源指针替换旧数据。 */
  @Override
  public String sourceVersion() {
    List<String> ids = new ArrayList<>(datasets.stream().map(context -> context.publicationId().toString()).toList());
    if (facts != null) { ids.addAll(facts.contexts().stream().map(context -> context.publicationId().toString()).toList()); }
    ids.sort(String::compareTo);
    try {
      return "publication:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest((String.join("/", ids) + "/" + businessDate + "/" + ReadModelRules.VERSION)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用", error);
    }
  }

  /** 数据集主身份仅用于缓存归属，仍保留完整组合查询。 */
  @Override
  public UUID primaryPublicationId() {
    if (facts != null) { return facts.primaryPublicationId(); }
    return datasets.stream().min(java.util.Comparator.<PublishedDatasetReadContext>comparingInt(
            context -> "formal-records".equals(context.datasetKey()) ? 0 : 1)
        .thenComparing(PublishedDatasetReadContext::datasetKey)
        .thenComparing(PublishedDatasetReadContext::sourceInstance)
        .thenComparing(context -> context.publicationId().toString())).orElseThrow().publicationId();
  }

  /** 公开数据集不接受候选身份。 */
  @Override
  public boolean candidate() { return false; }
}
