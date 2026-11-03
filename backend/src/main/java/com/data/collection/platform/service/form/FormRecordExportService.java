package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.exception.FormPayloadTooLargeException;
import com.data.collection.platform.common.exception.FormResourceNotFoundException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.service.ExcelExportStyles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 按版本导出有效表单记录：版本/项目/目录 C 三重谓词、冻结规格列序、审计与有界工作簿。
 *
 * <p>只读 {@code AUTHORITATIVE} 且已发布/退役版本；作废记录不导出。列名与行值都来自同一份
 * 冻结规格，系统列固定在前；空版本仍输出表头并记录审计。超过行数或字节预算时显式 413，
 * 不静默截断。
 */
@Service
public class FormRecordExportService {

  static final int DEFAULT_MAX_ROWS = 50_000;
  static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;
  private static final int STREAMING_THRESHOLD = 5_000;
  private static final int STREAMING_WINDOW = 200;
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final java.util.regex.Pattern SIMPLE_TABLE =
      java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{1,62}$");
  private static final List<String> SYSTEM_HEADERS =
      List.of("记录ID", "GitLab地址", "项目", "资源类型", "资源ID", "创建人", "创建时间",
          "记录修订", "结构版本", "状态");

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordDirectoryReader reader;
  private final FormRecordContextService contextService;
  private final ObjectMapper objectMapper;
  private final int maxRows;
  private final long maxBytes;

  @Autowired
  public FormRecordExportService(
      JdbcTemplate jdbcTemplate,
      FormRecordDirectoryReader reader,
      FormRecordContextService contextService,
      ObjectMapper objectMapper) {
    this(jdbcTemplate, reader, contextService, objectMapper, DEFAULT_MAX_ROWS, DEFAULT_MAX_BYTES);
  }

  FormRecordExportService(
      JdbcTemplate jdbcTemplate,
      FormRecordDirectoryReader reader,
      FormRecordContextService contextService,
      ObjectMapper objectMapper,
      int maxRows,
      long maxBytes) {
    this.jdbcTemplate = jdbcTemplate;
    this.reader = reader;
    this.contextService = contextService;
    this.objectMapper = objectMapper;
    this.maxRows = maxRows;
    this.maxBytes = maxBytes;
  }

  /** 导出的工作簿与审计所需的文件摘要。 */
  public record ExportResult(
      byte[] content, String filename, int rowCount, long byteSize, String sha256) {}

  /**
   * 导出指定版本的有效记录并写入审计。
   *
   * @param versionId 可读权威版本
   * @param requestedProjects 请求项目；空表示该范围全部项目
   * @param operatorId 操作人稳定账号
   * @return 工作簿内容、文件名、行数与摘要
   */
  public ExportResult export(long versionId, List<Long> requestedProjects, String operatorId) {
    VersionRow version = loadVersion(versionId);
    List<Long> projects = resolveProjects(version.projectIds(), requestedProjects);
    List<FormRecordDirectoryReader.LocatedRecord> records = loadRecords(version, projects);
    List<String> headers = headers(version.spec());
    byte[] content = writeWorkbook(version, headers, records);
    if (content.length > maxBytes) {
      throw new FormPayloadTooLargeException(
          "表单记录导出文件超过上限：实际=" + content.length + "，上限=" + maxBytes);
    }
    String sha256 = sha256(content);
    jdbcTemplate.update(
        """
        insert into form_record_export_logs(
            scope_id, version_id, project_ids, row_count, byte_size, file_sha256, operator_id)
        values (?, ?, ?::jsonb, ?, ?, ?, ?)
        """,
        version.scopeId(),
        version.versionId(),
        writeJson(projects),
        records.size(),
        content.length,
        sha256,
        operatorId);
    return new ExportResult(
        content, filename(version), records.size(), content.length, sha256);
  }

  private VersionRow loadVersion(long versionId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select v.id, v.scope_id, v.version_label, v.field_spec::text as field_spec,
                   v.record_source_role, v.version_status, v.instance_mode, v.record_table,
                   v.source_table, s.template_key, s.project_ids::text as project_ids,
                   p.frozen_binding::text as frozen_binding
              from form_template_versions v
              join form_template_scopes s on s.id = v.scope_id
              left join form_template_publications p on p.to_version_id = v.id
             where v.id = ?
            """,
            versionId);
    if (rows.isEmpty()) {
      throw new FormResourceNotFoundException("表单结构版本不存在：" + versionId);
    }
    Map<String, Object> row = rows.getFirst();
    if (!"AUTHORITATIVE".equals(row.get("record_source_role"))
        || !("PUBLISHED".equals(row.get("version_status"))
            || "RETIRED".equals(row.get("version_status")))) {
      throw new FormConflictException("只有已发布或已退役的权威版本可导出：" + versionId);
    }
    long scopeId = ((Number) row.get("scope_id")).longValue();
    String templateKey = (String) row.get("template_key");
    FormTemplateSpec spec =
        contextService.parseSpec((String) row.get("field_spec"), templateKey);
    return new VersionRow(
        versionId,
        scopeId,
        templateKey,
        (String) row.get("version_label"),
        (String) row.get("instance_mode"),
        (String) row.get("record_table"),
        (String) row.get("source_table"),
        spec,
        versionProjectIds((String) row.get("frozen_binding"), (String) row.get("project_ids")));
  }

  private List<Long> versionProjectIds(String frozenBinding, String scopeProjects) {
    if (frozenBinding != null && !frozenBinding.isBlank()) {
      try {
        JsonNode projectIds = objectMapper.readTree(frozenBinding).path("projectIds");
        if (projectIds.isArray()) {
          return parseProjectIds(projectIds.toString());
        }
      } catch (Exception error) {
        throw new IllegalStateException("表单版本冻结绑定无法解析", error);
      }
    }
    return parseProjectIds(scopeProjects);
  }

  private List<Long> resolveProjects(List<Long> scopeProjects, List<Long> requested) {
    if (scopeProjects.isEmpty()) {
      throw new FormConflictException("表单范围未绑定项目，不能导出");
    }
    if (requested == null || requested.isEmpty()) {
      return scopeProjects;
    }
    LinkedHashSet<Long> distinct = new LinkedHashSet<>();
    for (Long projectId : requested) {
      if (projectId == null) {
        throw new BizException(ResultCode.BAD_REQUEST, "导出项目 ID 不能为空");
      }
      distinct.add(projectId);
    }
    if (distinct.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "导出项目集合不能为空");
    }
    if (!scopeProjects.containsAll(distinct)) {
      throw new BizException(ResultCode.BAD_REQUEST, "导出项目必须属于该版本所在范围");
    }
    return List.copyOf(distinct);
  }

  private List<FormRecordDirectoryReader.LocatedRecord> loadRecords(
      VersionRow version, List<Long> projects) {
    String placeholders = String.join(", ", java.util.Collections.nCopies(projects.size(), "?"));
    List<Object> args = new ArrayList<>(projects.size() + 3);
    args.add(version.scopeId());
    args.add(version.versionId());
    args.addAll(projects);
    args.add(maxRows + 1);
    List<Long> recordIds =
        jdbcTemplate.queryForList(
            """
            select l.record_id
              from form_record_locations l
              join form_template_versions v on v.id = l.storage_version_id
              %s
             where l.scope_id = ? and l.storage_version_id = ?
               and l.project_id in (%s)
               and v.record_source_role = 'AUTHORITATIVE'
               and v.version_status in ('PUBLISHED', 'RETIRED')
             order by l.created_at, l.record_id
             limit ?
            """
                .formatted(activeRecordJoin(version), placeholders),
            Long.class,
            args.toArray());
    if (recordIds.size() > maxRows) {
      throw new FormPayloadTooLargeException(
          "表单记录导出行数超过上限：上限=" + maxRows);
    }
    List<FormRecordDirectoryReader.LocatedRecord> records = new ArrayList<>(recordIds.size());
    for (Long recordId : recordIds) {
      FormRecordDirectoryReader.LocatedRecord record = reader.find(recordId).orElse(null);
      if (record == null) {
        continue;
      }
      if (record.storageVersionId() != version.versionId()
          || !projects.contains(record.projectId())) {
        throw new FormConflictException("导出记录目录 C 不在请求范围内：record=" + recordId);
      }
      records.add(record);
    }
    return List.copyOf(records);
  }

  /** 只在目录候选阶段连接承载表排除作废行，读取预算最多探测超限一行。 */
  private String activeRecordJoin(VersionRow version) {
    String table;
    if ("MATERIALIZED".equals(version.instanceMode())) {
      table = version.recordTable();
    } else if ("NONE".equals(version.instanceMode())) {
      table = version.sourceTable();
    } else {
      throw new FormConflictException("承载版本实例模式不可导出：" + version.instanceMode());
    }
    if (table == null || !SIMPLE_TABLE.matcher(table).matches()) {
      throw new FormConflictException("承载表名非法，无法导出：" + table);
    }
    return "join " + table + " ar on ar.id = l.record_id and coalesce(ar.deleted, false) = false";
  }

  private List<String> headers(FormTemplateSpec spec) {
    List<String> headers = new ArrayList<>(SYSTEM_HEADERS);
    for (FormFieldSpec field : spec.fields()) {
      String label = field.label();
      headers.add(label == null || label.isBlank() ? field.name() : label);
    }
    return List.copyOf(headers);
  }

  private byte[] writeWorkbook(
      VersionRow version,
      List<String> headers,
      List<FormRecordDirectoryReader.LocatedRecord> records) {
    boolean streaming = records.size() > STREAMING_THRESHOLD;
    Workbook workbook = null;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        BoundedOutputStream bounded = new BoundedOutputStream(output, maxBytes)) {
      if (streaming) {
        workbook = new SXSSFWorkbook(STREAMING_WINDOW);
      } else {
        workbook = new XSSFWorkbook();
      }
      Sheet sheet = workbook.createSheet(sheetName(version));
      CellStyle headerStyle = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle bodyStyle = ExcelExportStyles.createBodyStyle(workbook);
      writeRow(sheet.createRow(0), headers, headerStyle);
      Map<String, String> displayNames = new HashMap<>();
      int rowIndex = 1;
      for (FormRecordDirectoryReader.LocatedRecord record : records) {
        Row row = sheet.createRow(rowIndex++);
        writeRow(row, systemRow(version, record, displayNames), bodyStyle);
        int column = SYSTEM_HEADERS.size();
        for (FormFieldSpec field : version.spec().fields()) {
          writeCell(row, column++, value(record, field), bodyStyle);
        }
      }
      for (int index = 0; index < headers.size(); index++) {
        sheet.setColumnWidth(index, 18 * 256);
      }
      sheet.createFreezePane(0, 1);
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      workbook.write(bounded);
      bounded.flush();
      return output.toByteArray();
    } catch (FormPayloadTooLargeException error) {
      throw error;
    } catch (Exception error) {
      FormPayloadTooLargeException overflow = findOverflow(error);
      if (overflow != null) {
        throw overflow;
      }
      throw new IllegalStateException("表单记录 Excel 导出失败", error);
    } finally {
      if (workbook instanceof SXSSFWorkbook sxssf) {
        sxssf.dispose();
      }
      if (workbook != null) {
        try {
          workbook.close();
        } catch (Exception ignored) {
          // 关闭失败不覆盖导出结果
        }
      }
    }
  }

  private List<String> systemRow(
      VersionRow version,
      FormRecordDirectoryReader.LocatedRecord record,
      Map<String, String> displayNames) {
    return List.of(
        Long.toString(record.recordId()),
        nullToEmpty(record.gitlabBaseUrl()),
        Long.toString(record.projectId()),
        nullToEmpty(record.resourceType()),
        nullToEmpty(record.resourceId()),
        displayName(record.createdBy(), displayNames),
        format(record.createdAt()),
        record.recordVersion() == null ? "" : Integer.toString(record.recordVersion()),
        version.versionLabel() == null
            ? Long.toString(record.storageVersionId())
            : version.versionLabel(),
        "有效");
  }

  private String value(
      FormRecordDirectoryReader.LocatedRecord record, FormFieldSpec field) {
    if (field.storage() == null) {
      return "";
    }
    String column =
        field.storage().nameColumn() != null
            ? field.storage().nameColumn()
            : field.storage().resolvedColumn();
    if (column == null) {
      return "";
    }
    Object raw = record.fields().get(column);
    if (raw == null) {
      return "";
    }
    return format(raw);
  }

  private String format(Object raw) {
    if (raw == null) {
      return "";
    }
    if (raw instanceof LocalDate value) {
      return DATE.format(value);
    }
    if (raw instanceof LocalDateTime value) {
      return DATE_TIME.format(value);
    }
    if (raw instanceof java.sql.Timestamp value) {
      return DATE_TIME.format(value.toLocalDateTime());
    }
    if (raw instanceof java.sql.Date value) {
      return DATE.format(value.toLocalDate());
    }
    if (raw instanceof OffsetDateTime value) {
      return DATE_TIME.format(value.atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime());
    }
    if (raw instanceof Instant value) {
      return DATE_TIME.format(value.atZone(BUSINESS_ZONE).toLocalDateTime());
    }
    return String.valueOf(raw);
  }

  private String displayName(String createdBy, Map<String, String> cache) {
    if (createdBy == null || createdBy.isBlank()) {
      return "";
    }
    return cache.computeIfAbsent(
        createdBy,
        key -> {
          List<String> names =
              jdbcTemplate.queryForList(
                  "select real_name from platform_ldap_users where user_id = ?", String.class, key);
          return names.isEmpty() || names.getFirst() == null || names.getFirst().isBlank()
              ? key
              : names.getFirst();
        });
  }

  private void writeRow(Row row, List<String> values, CellStyle style) {
    for (int index = 0; index < values.size(); index++) {
      writeCell(row, index, values.get(index), style);
    }
  }

  private void writeCell(Row row, int column, String value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellStyle(style);
    cell.setCellValue(nullToEmpty(value));
  }

  private List<Long> parseProjectIds(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      List<Long> result = new ArrayList<>();
      for (var node : objectMapper.readTree(raw)) {
        if (node.canConvertToLong()) {
          result.add(node.asLong());
        }
      }
      return List.copyOf(result);
    } catch (Exception error) {
      throw new IllegalStateException("表单范围项目集合无法解析", error);
    }
  }

  private String writeJson(List<Long> projects) {
    try {
      return objectMapper.writeValueAsString(projects);
    } catch (Exception error) {
      throw new IllegalStateException("导出项目集合无法序列化", error);
    }
  }

  private String filename(VersionRow version) {
    return version.templateKey() + "-records-" + version.versionId() + ".xlsx";
  }

  private String sheetName(VersionRow version) {
    String raw = version.versionLabel();
    if (raw == null || raw.isBlank()) {
      raw = "表单记录";
    }
    String sanitized = raw.replaceAll("[\\\\/:*?\\[\\]]", "_");
    return sanitized.length() > 31 ? sanitized.substring(0, 31) : sanitized;
  }

  private static String sha256(byte[] content) {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception error) {
      throw new IllegalStateException("导出文件摘要计算失败", error);
    }
  }

  /** 预算异常可能被 POI 包装成 IO 异常；沿 cause 链恢复为显式 413。 */
  private static FormPayloadTooLargeException findOverflow(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof FormPayloadTooLargeException overflow) {
        return overflow;
      }
      current = current.getCause();
    }
    return null;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** 写出期间按字节预算中止，避免整份 byte[] 生成后才检查。 */
  private static final class BoundedOutputStream extends FilterOutputStream {
    private final long limit;
    private long count;

    BoundedOutputStream(OutputStream out, long limit) {
      super(out);
      this.limit = limit;
    }

    @Override
    public void write(int value) throws IOException {
      ensureCapacity(1);
      out.write(value);
      count++;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      ensureCapacity(length);
      out.write(buffer, offset, length);
      count += length;
    }

    private void ensureCapacity(int additional) {
      if (count + additional > limit) {
        throw new FormPayloadTooLargeException(
            "表单记录导出文件超过上限：上限=" + limit);
      }
    }
  }

  private record VersionRow(
      long versionId,
      long scopeId,
      String templateKey,
      String versionLabel,
      String instanceMode,
      String recordTable,
      String sourceTable,
      FormTemplateSpec spec,
      List<Long> projectIds) {}
}
