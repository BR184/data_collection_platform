package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormIssueCommentRendererTest {
  private final FormIssueCommentRenderer renderer = new FormIssueCommentRenderer();

  @Test
  void emptyCommentHasTwoNamedLinksAndNoEmptyForms() {
    String body = renderer.render("http://platform/response", "http://platform/reply", List.of(), List.of(), null);
    assertThat(body).contains("[点击填写问题响应](<http://platform/response>)", "[点击填写问题回复](<http://platform/reply>)")
        .contains("响应 0 条 · 回复 0 条", "暂未填写").doesNotContain("<details>");
  }

  @Test
  void everyRecordHasIndependentCompleteEscapedDetails() throws Exception {
    var spec = new ObjectMapper().readValue("""
        {"template_key":"issue_response","fields":[
        {"name":"problem_type","apiKey":"problemType","label":"问题类型","type":"enum","options":[{"value":"DEFECT","label":"缺陷"}],"storage":{"column":"problem_type"}},
        {"name":"reason","apiKey":"reason","label":"问题原因","type":"text","storage":{"column":"reason"}},
        {"name":"extra","apiKey":"extra","label":"补充说明","type":"text","storage":{"column":"extra"}}]}
        """, FormTemplateSpec.class);
    var one = new FormIssueCommentRenderer.CommentRecord(8, spec, Map.of("problem_type", "DEFECT", "reason", "完整正文\n</details><script>alert(1)</script>"));
    var two = new FormIssueCommentRenderer.CommentRecord(12, spec, Map.of("reason", "第二条"));
    String body = renderer.render("http://platform/response", "http://platform/reply", List.of(one, two), List.of(), LocalDateTime.of(2026,11,13,14,30));
    assertThat(body).contains("响应 2 条 · 回复 0 条", "2026-11-13 14:30", "响应记录 <code>#8</code>", "缺陷", "响应记录 <code>#12</code>", "完整正文<br>", "补充说明", "未填写")
        .contains("&lt;/details&gt;&lt;script&gt;").doesNotContain("查看完整内容", "<script>");
    assertThat(body.split("<details>", -1)).hasSize(3);
    assertThat(body.split("</details>", -1)).hasSize(3);
  }
}
