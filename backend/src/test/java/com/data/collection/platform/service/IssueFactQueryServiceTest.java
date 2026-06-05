package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class IssueFactQueryServiceTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private SqlQueryMonitor sqlQueryMonitor;

  @Test
  void moduleNameFilterShouldNotMatchContainingModuleToken() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    IssueFactQueryService service = new IssueFactQueryService(jdbcTemplate, sqlQueryMonitor);

    List<String> rows =
        service.query(
            "select module_names from issue_fact where deleted = false",
            Map.of("moduleName", "\u5de5\u5177"),
            (ResultSet rs, int rowNum) -> rs.getString("module_names"));

    assertThat(rows).isEmpty();
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?");
    assertThat(argsCaptor.getValue()).containsExactly("%,\u5de5\u5177,%");
  }
}
