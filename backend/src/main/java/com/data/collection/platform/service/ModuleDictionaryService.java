package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
// 模块字典服务负责把标签、备注和外部数据里的模块别名归一成标准模块名。
// 规则支持全局、issue 和 merge request 作用域，优先级越高越先命中。
public class ModuleDictionaryService {

  private static final String DOMAIN_COMMON = "COMMON";
  private static final String DOMAIN_ISSUE = "ISSUE";
  private static final String DOMAIN_MERGE_REQUEST = "MERGE_REQUEST";
  private static final List<String> GENERIC_SUFFIXES = List.of("功能模块", "业务模块", "子模块", "模块");

  private final JdbcTemplate jdbcTemplate;

  public ModuleDictionaryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public ModuleDictionary loadDictionary() {
    List<ModuleDictionaryRule> rules =
        jdbcTemplate.query(
            """
            select dictionary_domain,
                   project_id,
                   standard_module_name,
                   alias_name,
                   priority
              from module_dictionary
             where enabled = true
               and btrim(standard_module_name) <> ''
               and btrim(alias_name) <> ''
            """,
            this::mapRule);
    return new ModuleDictionary(rules);
  }

  private ModuleDictionaryRule mapRule(ResultSet rs, int rowNum) throws SQLException {
    Long projectId = rs.getObject("project_id", Long.class);
    String domain = normalizeDomain(rs.getString("dictionary_domain"));
    String standardName = cleanupDisplay(rs.getString("standard_module_name"));
    String alias = cleanupDisplay(rs.getString("alias_name"));
    int priority = rs.getInt("priority");
    return new ModuleDictionaryRule(domain, projectId, standardName, alias, priority);
  }

  private static String normalizeDomain(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? DOMAIN_COMMON : normalized.toUpperCase(Locale.ROOT);
  }

  private static String cleanupDisplay(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    return normalized.replaceAll("\\s+", " ");
  }

  private static String normalizeKey(String value) {
    String cleaned = stripGenericSuffix(cleanupDisplay(value));
    if (cleaned == null) {
      return null;
    }
    String lower = cleaned.toLowerCase(Locale.ROOT);
    return lower.replaceAll("[\\s_\\-./\\\\:：,，;；|｜()（）\\[\\]【】{}《》<>]+", "");
  }

  private static String stripGenericSuffix(String value) {
    String cleaned = cleanupDisplay(value);
    if (cleaned == null) {
      return null;
    }
    for (String suffix : GENERIC_SUFFIXES) {
      if (cleaned.endsWith(suffix)) {
        String stem = cleaned.substring(0, cleaned.length() - suffix.length()).trim();
        if (stem.length() >= 2) {
          return stem;
        }
      }
    }
    return cleaned;
  }

  public static final class ModuleDictionary {
    private final List<ModuleDictionaryRule> rules;

    private ModuleDictionary(List<ModuleDictionaryRule> rules) {
      this.rules =
          rules.stream()
              .filter(rule -> rule.standardName() != null && rule.alias() != null)
              .sorted(
                  Comparator.comparing(ModuleDictionaryRule::priority)
                      .reversed()
                      .thenComparing(rule -> rule.projectId() == null ? 1 : 0))
              .toList();
    }

    public List<String> normalizeIssueModules(Long projectId, List<String> rawModules) {
      return normalizeModules(DOMAIN_ISSUE, projectId, rawModules);
    }

    public String normalizeMergeRequestModule(Long projectId, String rawModule) {
      return normalizeModule(DOMAIN_MERGE_REQUEST, projectId, rawModule);
    }

    private List<String> normalizeModules(String domain, Long projectId, List<String> rawModules) {
      if (rawModules == null || rawModules.isEmpty()) {
        return List.of();
      }
      Set<String> result = new LinkedHashSet<>();
      for (String rawModule : rawModules) {
        String normalized = normalizeModule(domain, projectId, rawModule);
        if (StringUtils.hasText(normalized)) {
          result.add(normalized);
        }
      }
      return List.copyOf(result);
    }

    private String normalizeModule(String domain, Long projectId, String rawModule) {
      String cleaned = cleanupDisplay(rawModule);
      if (cleaned == null) {
        return null;
      }
      String rawKey = normalizeKey(cleaned);
      for (ModuleDictionaryRule rule : rules) {
        if (!domainMatches(rule.domain(), domain) || !projectMatches(rule.projectId(), projectId)) {
          continue;
        }
        if (Objects.equals(normalizeKey(rule.alias()), rawKey)
            || Objects.equals(normalizeKey(rule.standardName()), rawKey)) {
          return rule.standardName();
        }
      }
      return stripGenericSuffix(cleaned);
    }

    private boolean domainMatches(String ruleDomain, String targetDomain) {
      return Objects.equals(ruleDomain, DOMAIN_COMMON) || Objects.equals(ruleDomain, targetDomain);
    }

    private boolean projectMatches(Long ruleProjectId, Long targetProjectId) {
      return ruleProjectId == null || Objects.equals(ruleProjectId, targetProjectId);
    }
  }

  private record ModuleDictionaryRule(
      String domain, Long projectId, String standardName, String alias, int priority) {}
}
