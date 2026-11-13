package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 将冻结规格和有效记录渲染为GitLab原生折叠评论；不截断正文，不回写业务数据。 */
@Component
public class FormIssueCommentRenderer {
  private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  /** 字段值使用承载版本的物理列键，规格决定字段顺序与枚举显示名称。 */
  public record CommentRecord(long id, FormTemplateSpec spec, Map<String, Object> fields) {}

  /** 返回完整评论；链接由平台配置生成，正文全部转义，不允许输入注入HTML或折叠结构。 */
  public String render(String responseUrl, String replyUrl, List<CommentRecord> responses,
      List<CommentRecord> replies, LocalDateTime changedAt) {
    StringBuilder body = new StringBuilder("**表单填写入口**\n\n")
        .append("[点击填写问题响应](<").append(link(responseUrl)).append(">) · ")
        .append("[点击填写问题回复](<").append(link(replyUrl)).append(">)\n\n---\n\n")
        .append("**表单填写与记录**\n\n响应 ").append(responses.size()).append(" 条 · 回复 ")
        .append(replies.size()).append(" 条\n\n");
    if (changedAt != null) body.append("最后更新：").append(DISPLAY_TIME.format(changedAt)).append("\n\n");
    appendGroup(body, "响应", responses);
    appendGroup(body, "回复", replies);
    if (body.length() > 1_000_000) throw new IllegalStateException("完整表单评论超过GitLab长度上限，未截断，等待人工处理");
    return body.toString();
  }

  private void appendGroup(StringBuilder body, String name, List<CommentRecord> records) {
    body.append("**问题").append(name).append("（").append(records.size()).append("条）**\n\n");
    if (records.isEmpty()) body.append("暂未填写\n\n");
    for (CommentRecord record : records) {
      body.append("<details>\n<summary><strong>").append(name).append("记录 <code>#").append(record.id())
          .append("</code></strong>");
      if ("响应".equals(name)) {
        body.append(" · ").append(summaryValue(record, "problemType"))
            .append(" · 计划解决：").append(summaryValue(record, "plannedResolutionDate"));
      } else body.append(" · ").append(summaryValue(record, "fixStatus"));
      body.append("</summary>\n\n");
      for (FormFieldSpec field : record.spec().fields()) {
        body.append("<p><strong>").append(escape(field.label())).append("：</strong><br>")
            .append(escape(displayValue(record, field))).append("</p>\n\n");
      }
      body.append("</details>\n\n");
    }
  }

  private String summaryValue(CommentRecord record, String apiKey) {
    return record.spec().fields().stream().filter(f -> apiKey.equals(f.apiKey()))
        .findFirst().map(f -> escape(displayValue(record, f)).replace("<br>", " "))
        .orElse("未填写");
  }

  private String displayValue(CommentRecord record, FormFieldSpec field) {
    if (field.storage() == null) throw new IllegalStateException("表单字段缺少物理绑定：" + field.name());
    String column = field.storage().nameColumn() != null ? field.storage().nameColumn() : field.storage().resolvedColumn();
    Object value = record.fields().get(column);
    if (value instanceof java.sql.Array array) {
      try { value = array.getArray(); }
      catch (java.sql.SQLException error) { throw new IllegalStateException("表单数组无法读取", error); }
    }
    if (value == null || value.toString().isBlank()) return "未填写";
    if (value instanceof Collection<?> values) {
      return values.isEmpty() ? "未填写" : values.stream().map(v -> label(record.spec(), field, v)).collect(Collectors.joining("、"));
    }
    if (value instanceof Object[] values) {
      return values.length == 0 ? "未填写" : java.util.Arrays.stream(values).map(v -> label(record.spec(), field, v)).collect(Collectors.joining("、"));
    }
    return label(record.spec(), field, value);
  }

  private String label(FormTemplateSpec spec, FormFieldSpec field, Object value) {
    String raw = String.valueOf(value);
    if (field.options() != null) {
      var option = field.options().stream().filter(o -> raw.equals(o.value())).findFirst();
      if (option.isPresent()) return option.get().label();
    }
    if (field.dictionary() != null) {
      var dictionary = spec.dictionary(field.dictionary());
      if (dictionary.isPresent() && dictionary.get().items() != null) {
        var item = dictionary.get().items().stream().filter(i -> raw.equals(i.key())).findFirst();
        if (item.isPresent()) return item.get().label();
      }
    }
    if (value instanceof Boolean bool) return bool ? "是" : "否";
    return raw;
  }

  private static String link(String url) {
    return url.replace("<", "%3C").replace(">", "%3E").replace("\n", "%0A").replace("\r", "%0D").replace(" ", "%20");
  }

  private static String escape(String text) {
    if (text == null) return "";
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
  }
}
