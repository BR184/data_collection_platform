package com.data.collection.platform.service.form;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 表单消费输入的规范签名。
 *
 * <p>签名只覆盖读取身份与权威/集合/head 修订，不覆盖业务值；同一输入的构建、事实与作业使用同一
 * 编码，发送前任一侧变化都表现为签名不等。空值统一编码为 {@code -}，侧按模板键排序，避免集合
 * 顺序或数据库 NULL 表示差异造成假变化。
 */
public final class FormInputHash {
  private static final String NULL = "-";
  private static final String FIELD = "\u001f";
  private static final String RECORD = "\u001e";

  private FormInputHash() {}

  /** 一个模板侧的身份与证据修订。 */
  public record Side(
      String templateKey,
      String authority,
      Long authorityRevision,
      Long scopeId,
      Long sourceSetRevision,
      String sourceSetHash,
      Long headId,
      Long latestChangeVersion) {}

  /** 议题级签名：完整资源 C + 两侧权威/范围集合/head 修订。 */
  public static String issueHash(
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      long projectId,
      String resourceType,
      String resourceId,
      List<Side> sides) {
    StringBuilder value = new StringBuilder();
    append(value, "c");
    append(value, sourceConfigId);
    append(value, sourceInstance);
    append(value, canonicalGitlabBaseUrl);
    append(value, projectId);
    append(value, resourceType);
    append(value, resourceId);
    sides.stream()
        .sorted(Comparator.comparing(Side::templateKey))
        .forEach(side -> appendSide(value, side));
    return sha256(value.toString());
  }

  /** 发布候选输入清单签名：由已绑定证据行生成并按 head 排序。 */
  public static String manifestHash(List<String> evidenceLines) {
    return sha256(String.join("\n", evidenceLines.stream().sorted().toList()));
  }

  /** 证据行规范文本；调用方提供全部复核字段。 */
  public static String evidenceLine(
      long headId,
      long scopeId,
      Long authorityRevision,
      Long sourceSetRevision,
      String sourceSetHash,
      long latestChangeVersion,
      String status) {
    StringBuilder value = new StringBuilder();
    append(value, "e");
    append(value, headId);
    append(value, scopeId);
    append(value, authorityRevision);
    append(value, sourceSetRevision);
    append(value, sourceSetHash);
    append(value, latestChangeVersion);
    append(value, status);
    return value.toString();
  }

  private static void appendSide(StringBuilder value, Side side) {
    append(value, "s");
    append(value, side.templateKey());
    append(value, side.authority());
    append(value, side.authorityRevision());
    append(value, side.scopeId());
    append(value, side.sourceSetRevision());
    append(value, side.sourceSetHash());
    append(value, side.headId());
    append(value, side.latestChangeVersion());
    value.append(RECORD);
  }

  private static void append(StringBuilder value, Object part) {
    value.append(part == null || Objects.equals(part, "") ? NULL : part).append(FIELD);
  }

  private static String sha256(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        hex.append(Character.forDigit((value >> 4) & 0xF, 16));
        hex.append(Character.forDigit(value & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JVM 缺少 SHA-256", error);
    }
  }
}
