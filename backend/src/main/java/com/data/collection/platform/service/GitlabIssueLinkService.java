package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import org.springframework.jdbc.core.JdbcTemplate;

@Deprecated(forRemoval = false)
public class GitlabIssueLinkService extends GitlabResourceLinkService {
  public GitlabIssueLinkService(JdbcTemplate jdbcTemplate, GitlabMirrorProperties properties) {
    super(jdbcTemplate, properties);
  }
}
