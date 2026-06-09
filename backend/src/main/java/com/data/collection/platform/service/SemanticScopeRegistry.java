package com.data.collection.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SemanticScopeRegistry {
  private final Map<String, SemanticScope> scopes;

  public SemanticScopeRegistry() {
    this(defaultScopes());
  }

  SemanticScopeRegistry(List<SemanticScope> scopes) {
    Map<String, SemanticScope> ordered = new LinkedHashMap<>();
    for (SemanticScope scope : scopes) {
      ordered.put(scope.scopeKey(), scope);
    }
    this.scopes = Map.copyOf(ordered);
  }

  public static SemanticScopeRegistry withDefaults() {
    return new SemanticScopeRegistry(defaultScopes());
  }

  public SemanticScope resolve(String scopeKey) {
    SemanticScope scope = scopes.get(scopeKey);
    if (scope == null || !scope.enabled()) {
      throw new IllegalArgumentException("Unknown semantic scope: " + scopeKey);
    }
    return scope;
  }

  public SemanticScopePlan compose(List<String> scopeChain, ScopeCompositionMode mode) {
    if (scopeChain == null || scopeChain.isEmpty()) {
      throw new IllegalArgumentException("scopeChain must not be empty");
    }
    List<SemanticScope> resolved = scopeChain.stream().map(this::resolve).toList();
    String entityType = resolved.getFirst().entityType();
    validateCompatibility(entityType, scopeChain);
    List<String> sqlTemplateKeys = resolved.stream().map(SemanticScope::sqlTemplateKey).toList();
    return new SemanticScopePlan(
        entityType, mode, List.copyOf(scopeChain), sqlTemplateKeys, sha256(mode + ":" + scopeChain));
  }

  public void validateCompatibility(String entityType, List<String> scopeChain) {
    for (String scopeKey : scopeChain) {
      SemanticScope scope = resolve(scopeKey);
      if (!scope.entityType().equals(entityType)) {
        throw new IllegalArgumentException(
            "Semantic scope " + scopeKey + " does not support entity type " + entityType);
      }
    }
  }

  private static List<SemanticScope> defaultScopes() {
    return List.of(
        new SemanticScope(
            "system_test_issue_scope",
            "BASE",
            "issue",
            "System test issue range",
            "system_test_issue_scope",
            "docs/platform-page-business-rules.md#4",
            null,
            true),
        new SemanticScope(
            "customer_issue_base_scope",
            "BASE",
            "issue",
            "Customer issue base range",
            "customer_issue_base_scope",
            "docs/platform-page-business-rules.md#5",
            null,
            true),
        new SemanticScope(
            "customer_issue_open_scope",
            "FILTER",
            "issue",
            "Customer issue open state filter",
            "customer_issue_open_scope",
            "docs/platform-page-business-rules.md#5",
            null,
            true));
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }
}
