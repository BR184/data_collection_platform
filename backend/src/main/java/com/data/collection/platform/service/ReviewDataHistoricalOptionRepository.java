package com.data.collection.platform.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 从历史评审数据中提取候选值。
 * 按文档 6.8：评审版本和模块等字段在老平台是自由文本，应从历史数据中提取候选值。
 */
@Repository
@Slf4j
public class ReviewDataHistoricalOptionRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReviewDataHistoricalOptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 从历史评审数据中提取评审版本候选值。
   * 老平台的评审版本包括文档版本号（V0.1, V1.0）和项目代号（CC2025R1, CC2026R1）等自由文本。
   * 按最新使用时间倒序排列。
   */
  public List<String> loadReviewVersions() {
    return queryDistinct(
        """
        select review_version
          from review_data
         where coalesce(deleted, false) = false
           and nullif(trim(review_version), '') is not null
         group by review_version
         order by max(created_at) desc nulls last
         limit 200
        """,
        "review_data.review_version");
  }

  /**
   * 从历史评审数据中提取模块名称候选值。
   * 老平台的模块包括产品名称、项目代号、功能模块等多种类型的自由文本。
   * 按最新使用时间倒序排列。
   */
  public List<String> loadModuleNames() {
    return queryDistinct(
        """
        select module_name
          from review_data
         where coalesce(deleted, false) = false
           and nullif(trim(module_name), '') is not null
         group by module_name
         order by max(created_at) desc nulls last
         limit 200
        """,
        "review_data.module_name");
  }

  /**
   * 从历史评审数据中提取项目名称候选值。
   * 补充 GitLab 镜像库中可能缺失的历史项目。
   * 按最新使用时间倒序排列。
   */
  public List<String> loadProjectNames() {
    return queryDistinct(
        """
        select project_name
          from review_data
         where coalesce(deleted, false) = false
           and nullif(trim(project_name), '') is not null
         group by project_name
         order by max(created_at) desc nulls last
         limit 200
        """,
        "review_data.project_name");
  }

  /**
   * 从历史评审数据中提取评审负责人候选值。
   * 按最新使用时间倒序排列。
   */
  public List<String> loadReviewOwners() {
    return queryDistinct(
        """
        select review_owner
          from review_data
         where coalesce(deleted, false) = false
           and nullif(trim(review_owner), '') is not null
         group by review_owner
         order by max(created_at) desc nulls last
         limit 200
        """,
        "review_data.review_owner");
  }

  /**
   * 从历史评审数据中提取评审专家候选值。
   * 评审专家是数组字段，需要展开后去重。
   * 按最新使用时间倒序排列。
   */
  public List<String> loadReviewExperts() {
    return queryDistinct(
        """
        select unnest(review_experts) as expert_name
          from review_data
         where coalesce(deleted, false) = false
           and review_experts is not null
           and array_length(review_experts, 1) > 0
         group by expert_name
        having nullif(trim(expert_name), '') is not null
         order by count(*) desc
         limit 200
        """,
        "review_data.review_experts");
  }

  /**
   * 从历史评审数据的工作产品描述中提取作者候选值。
   * 按最新使用时间倒序排列。
   */
  public List<String> loadAuthors() {
    return queryDistinct(
        """
        select author_name
          from review_data_description
         where coalesce(deleted, false) = false
           and nullif(trim(author_name), '') is not null
         group by author_name
         order by max(created_at) desc nulls last
         limit 200
        """,
        "review_data_description.author_name");
  }

  private List<String> queryDistinct(String sql, String sourceName) {
    try {
      return jdbcTemplate.queryForList(sql, String.class);
    } catch (DataAccessException error) {
      log.debug("Review data historical option source {} is unavailable", sourceName, error);
      return List.of();
    }
  }
}
