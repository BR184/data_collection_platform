package com.data.collection.platform.domain.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 表单规格、词典与 DDL 的规范化哈希。 */
public final class FormHashSupport {

  private FormHashSupport() {}

  public static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  public static String canonicalDictionaryHash(
      ObjectMapper objectMapper, FormDictionarySpec dictionary) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("name", dictionary.name());
    root.put("version", dictionary.version());
    // source 是追溯元数据，不参与内容哈希；批准哈希按 name/version/items 计算。
    ArrayNode items = root.putArray("items");
    for (FormDictionaryItem item : dictionary.items()) {
      ObjectNode node = items.addObject();
      node.put("key", item.key());
      node.put("label", item.label());
      node.put("group", item.group());
    }
    try {
      return sha256Hex(objectMapper.writeValueAsString(root));
    } catch (Exception error) {
      throw new IllegalStateException("Failed to canonicalize form dictionary", error);
    }
  }
}
