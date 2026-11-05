package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.exception.FormResourceNotFoundException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormDictionarySpec;
import com.data.collection.platform.domain.form.FormFieldOption;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormStorageSpec;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.service.form.FormComparableRegistrationService.FieldDeclaration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 共同字段选择与跨版聚合（N0 §7.1.1）：稳定身份、语义键、冻结类型/单位与冻结域共同决定可比性。
 *
 * <p>选择结果只暴露有效合同；未声明、域无法解析、声明与冻结类型冲突等进入显式无效报告，
 * 永不参与共同字段。聚合只消费选择结果中的共同合同，按记录求和并分别统计缺字段、NULL、0、
 * 作废与非数值，不直接读取规格常量或未经筛选的原始记录。
 */
@Service
public class FormCommonFieldService {

  private static final String STATUS_OK = "OK";
  private static final String STATUS_NO_COMMON_FIELDS = "NO_COMMON_FIELDS";
  private static final String STATUS_NO_COMMON_FIELD = "NO_COMMON_FIELD";
  private static final String STATUS_UNSUPPORTED_AGGREGATION = "UNSUPPORTED_AGGREGATION";
  private static final Set<String> KNOWN_TYPES =
      Set.of("string", "integer", "enum", "enum-array", "date", "boolean");
  private static final String TYPE_DECLARATION_CONFLICT = "TYPE_DECLARATION_CONFLICT";
  private static final String UNKNOWN_FROZEN_TYPE = "UNKNOWN_FROZEN_TYPE";
  private static final String DOMAIN_UNRESOLVED = "DOMAIN_UNRESOLVED";
  private static final String STORAGE_UNRESOLVED = "STORAGE_UNRESOLVED";
  private static final String UNDECLARED_FIELD = "UNDECLARED_FIELD";

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordContextService contextService;
  private final FormRecordDirectoryReader reader;
  private final FormComparableRegistrationService registrationService;
  private final ObjectMapper objectMapper;

  public FormCommonFieldService(
      JdbcTemplate jdbcTemplate,
      FormRecordContextService contextService,
      FormRecordDirectoryReader reader,
      FormComparableRegistrationService registrationService,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.contextService = contextService;
    this.reader = reader;
    this.registrationService = registrationService;
    this.objectMapper = objectMapper;
  }

  /** 参与共同字段选择的一个版本：冻结规格、已登记声明与该版本记录。 */
  public record VersionInput(
      long versionId,
      long scopeId,
      String templateKey,
      FormTemplateSpec spec,
      Map<String, FieldDeclaration> declarations,
      List<RecordValue> records) {}

  /** 单条记录输入；作废记录保留 deleted=true，由聚合显式计数。 */
  public record RecordValue(
      long recordId, long versionId, boolean deleted, Map<String, Object> fields) {}

  /** 有效字段合同。 */
  public record FieldContract(
      long versionId,
      String stableId,
      String name,
      String apiKey,
      String semanticKey,
      String valueType,
      String unit,
      int semanticVersion,
      String domainKind,
      String domainHash,
      String semanticHash) {}

  /** 显式无效合同；该字段永不进入 common。 */
  public record InvalidContract(
      long versionId,
      String invalidReason,
      String semanticKey,
      String name,
      String declaredType,
      String actualType) {}

  public record FieldVariant(
      long versionId,
      String stableId,
      String semanticKey,
      String valueType,
      String unit,
      String semanticHash) {}

  public record UnmergedField(String stableId, String reason, List<FieldVariant> variants) {}

  public record OnlyInSomeField(
      String stableId, List<Long> versions, List<Long> missingVersions) {}

  /** 共同字段选择结果。 */
  public record Selection(
      String status,
      List<FieldContract> common,
      List<UnmergedField> unmerged,
      List<OnlyInSomeField> onlyInSome,
      List<InvalidContract> invalidContracts,
      List<Long> selectedVersions,
      Map<Long, List<FieldContract>> perVersion) {}

  /** 单个版本对指标的贡献；total 为该版本有效数值之和。 */
  public record Contribution(
      long versionId,
      Long total,
      int records,
      int numeric,
      int missing,
      int nulls,
      int zeros,
      int nonNumeric,
      int deleted) {}

  /** 指标聚合结果；无共同字段或类型不支持时 total=null，不伪造 0。 */
  public record MetricResult(
      String status,
      String metric,
      Long total,
      List<Contribution> contributions,
      List<Long> contributingVersions,
      String invalidReason,
      int records,
      int numeric,
      int missing,
      int nulls,
      int zeros,
      int nonNumeric,
      int deleted,
      List<Long> selectedVersions) {}

  /**
   * 按版本 ID 加载冻结规格、已登记声明与记录（含作废，供统计计数）。
   *
   * @param versionIds 选择版本，顺序即输出顺序
   * @return 选择输入；版本不存在或不可读时显式拒绝
   */
  public List<VersionInput> loadVersions(List<Long> versionIds) {
    if (versionIds == null || versionIds.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "共同字段统计必须选择至少一个版本");
    }
    List<VersionInput> result = new ArrayList<>(versionIds.size());
    for (Long versionId : versionIds) {
      if (versionId == null) {
        throw new BizException(ResultCode.BAD_REQUEST, "版本 ID 不能为空");
      }
      List<Map<String, Object>> rows =
          jdbcTemplate.queryForList(
              """
              select v.id, v.scope_id, v.field_spec::text as field_spec,
                     v.record_source_role, v.version_status, s.template_key
                from form_template_versions v
                join form_template_scopes s on s.id = v.scope_id
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
        throw new FormConflictException("只有已发布或已退役的权威版本可参与共同字段统计："
            + versionId);
      }
      long scopeId = ((Number) row.get("scope_id")).longValue();
      String templateKey = (String) row.get("template_key");
      FormTemplateSpec spec =
          contextService.parseSpec((String) row.get("field_spec"), templateKey);
      List<RecordValue> records = new ArrayList<>();
      for (FormRecordDirectoryReader.LocatedRecord record :
          reader.listByVersionIncludingVoided(scopeId, versionId)) {
        records.add(
            new RecordValue(
                record.recordId(), versionId, record.deleted(), record.fields()));
      }
      result.add(
          new VersionInput(
              versionId,
              scopeId,
              templateKey,
              spec,
              registrationService.declarationsForVersion(versionId),
              List.copyOf(records)));
    }
    return List.copyOf(result);
  }

  /** 加载并选择共同字段。 */
  public Selection selectForVersions(List<Long> versionIds) {
    return select(loadVersions(versionIds));
  }

  /** 加载并聚合指定语义键。 */
  public MetricResult aggregateForVersions(List<Long> versionIds, String semanticKey) {
    List<VersionInput> versions = loadVersions(versionIds);
    return aggregate(select(versions), versions, semanticKey);
  }

  /** 对已加载版本执行共同字段选择。 */
  public Selection select(List<VersionInput> versions) {
    if (versions == null || versions.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "共同字段统计必须选择至少一个版本");
    }
    List<Long> selectedVersions = versions.stream().map(VersionInput::versionId).toList();
    List<InvalidContract> invalidContracts = new ArrayList<>();
    Map<Long, List<FieldContract>> perVersion = new LinkedHashMap<>();
    Map<Long, Map<String, FieldContract>> contractsByVersion = new LinkedHashMap<>();
    for (VersionInput version : versions) {
      contractsByVersion.put(
          version.versionId(), contractsOf(version, invalidContracts));
    }
    Map<String, FieldContract> first = contractsByVersion.get(versions.getFirst().versionId());
    LinkedHashSet<String> candidate = new LinkedHashSet<>(first.keySet());
    for (VersionInput version : versions) {
      candidate.retainAll(contractsByVersion.get(version.versionId()).keySet());
    }
    LinkedHashSet<String> union = new LinkedHashSet<>();
    for (VersionInput version : versions) {
      union.addAll(contractsByVersion.get(version.versionId()).keySet());
    }
    List<OnlyInSomeField> onlyInSome = new ArrayList<>();
    for (String stableId : union) {
      if (candidate.contains(stableId)) {
        continue;
      }
      List<Long> present = new ArrayList<>();
      List<Long> missing = new ArrayList<>();
      for (VersionInput version : versions) {
        if (contractsByVersion.get(version.versionId()).containsKey(stableId)) {
          present.add(version.versionId());
        } else {
          missing.add(version.versionId());
        }
      }
      onlyInSome.add(new OnlyInSomeField(stableId, List.copyOf(present), List.copyOf(missing)));
    }
    List<FieldContract> common = new ArrayList<>();
    List<UnmergedField> unmerged = new ArrayList<>();
    for (String stableId : candidate) {
      List<FieldContract> variants = new ArrayList<>();
      for (VersionInput version : versions) {
        variants.add(contractsByVersion.get(version.versionId()).get(stableId));
      }
      Set<String> signatures = new LinkedHashSet<>();
      Set<String> typeUnits = new LinkedHashSet<>();
      for (FieldContract variant : variants) {
        signatures.add(
            variant.semanticKey()
                + '\u0000'
                + variant.valueType()
                + '\u0000'
                + variant.unit()
                + '\u0000'
                + variant.semanticHash());
        typeUnits.add(variant.valueType() + '\u0000' + variant.unit());
      }
      if (signatures.size() == 1) {
        common.add(variants.getFirst());
      } else {
        unmerged.add(
            new UnmergedField(
                stableId,
                typeUnits.size() > 1
                    ? "incompatible_value_type"
                    : "identity_or_semantic_changed",
                variants.stream()
                    .map(
                        variant ->
                            new FieldVariant(
                                variant.versionId(),
                                variant.stableId(),
                                variant.semanticKey(),
                                variant.valueType(),
                                variant.unit(),
                                variant.semanticHash()))
                    .toList()));
      }
    }
    for (VersionInput version : versions) {
      perVersion.put(
          version.versionId(), List.copyOf(contractsByVersion.get(version.versionId()).values()));
    }
    String status = common.isEmpty() ? STATUS_NO_COMMON_FIELDS : STATUS_OK;
    return new Selection(
        status,
        List.copyOf(common),
        List.copyOf(unmerged),
        List.copyOf(onlyInSome),
        List.copyOf(invalidContracts),
        selectedVersions,
        Collections.unmodifiableMap(perVersion));
  }

  /** 对已选共同字段按语义键聚合整数指标。 */
  public MetricResult aggregate(
      Selection selection, List<VersionInput> versions, String semanticKey) {
    if (selection == null || versions == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "共同字段聚合缺少选择结果");
    }
    if (semanticKey == null || semanticKey.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "聚合指标语义键不能为空");
    }
    FieldContract contract =
        selection.common().stream()
            .filter(candidate -> semanticKey.equals(candidate.semanticKey()))
            .findFirst()
            .orElse(null);
    if (contract == null) {
      String reason =
          selection.invalidContracts().stream()
              .filter(invalid -> semanticKey.equals(invalid.semanticKey()))
              .map(InvalidContract::invalidReason)
              .findFirst()
              .orElse(null);
      String status =
          selection.status().equals(STATUS_NO_COMMON_FIELDS)
              ? STATUS_NO_COMMON_FIELDS
              : STATUS_NO_COMMON_FIELD;
      return emptyMetric(status, semanticKey, reason, selection.selectedVersions());
    }
    if (!"integer".equals(contract.valueType())) {
      return emptyMetric(
          STATUS_UNSUPPORTED_AGGREGATION,
          semanticKey,
          contract.valueType(),
          selection.selectedVersions());
    }
    Map<Long, VersionInput> byId = new LinkedHashMap<>();
    for (VersionInput version : versions) {
      byId.put(version.versionId(), version);
    }
    List<Contribution> contributions = new ArrayList<>();
    long total = 0L;
    boolean hasNumeric = false;
    int allRecords = 0;
    int allNumeric = 0;
    int allMissing = 0;
    int allNulls = 0;
    int allZeros = 0;
    int allNonNumeric = 0;
    int allDeleted = 0;
    List<Long> contributingVersions = new ArrayList<>();
    for (Long versionId : selection.selectedVersions()) {
      VersionInput version = byId.get(versionId);
      FieldContract versionContract = findVersionContract(selection, versionId, semanticKey);
      FormFieldSpec field =
          versionContract == null ? null : version.spec().fieldByName(versionContract.name()).orElse(null);
      String column = storageColumn(field);
      int records = 0;
      int numeric = 0;
      int missing = 0;
      int nulls = 0;
      int zeros = 0;
      int nonNumeric = 0;
      int deleted = 0;
      Long versionTotal = null;
      for (RecordValue record : version.records()) {
        if (record.deleted()) {
          deleted++;
          continue;
        }
        records++;
        if (column == null || !record.fields().containsKey(column)) {
          missing++;
          continue;
        }
        Object raw = record.fields().get(column);
        if (raw == null) {
          nulls++;
          continue;
        }
        Long value = integerValue(raw);
        if (value == null) {
          nonNumeric++;
          continue;
        }
        numeric++;
        versionTotal = (versionTotal == null ? 0L : versionTotal) + value;
        if (value == 0L) {
          zeros++;
        }
      }
      if (numeric > 0) {
        total += versionTotal;
        hasNumeric = true;
        contributingVersions.add(versionId);
      }
      contributions.add(
          new Contribution(
              versionId, versionTotal, records, numeric, missing, nulls, zeros, nonNumeric,
              deleted));
      allRecords += records;
      allNumeric += numeric;
      allMissing += missing;
      allNulls += nulls;
      allZeros += zeros;
      allNonNumeric += nonNumeric;
      allDeleted += deleted;
    }
    return new MetricResult(
        STATUS_OK,
        semanticKey,
        hasNumeric ? total : null,
        List.copyOf(contributions),
        List.copyOf(contributingVersions),
        null,
        allRecords,
        allNumeric,
        allMissing,
        allNulls,
        allZeros,
        allNonNumeric,
        allDeleted,
        selection.selectedVersions());
  }

  private Map<String, FieldContract> contractsOf(
      VersionInput version, List<InvalidContract> invalidContracts) {
    Map<String, FieldContract> contracts = new LinkedHashMap<>();
    Set<String> stableIds = new LinkedHashSet<>();
    Set<String> semanticKeys = new LinkedHashSet<>();
    for (FormFieldSpec field : version.spec().fields()) {
      FieldDeclaration declaration = version.declarations().get(field.name());
      if (declaration == null) {
        invalidContracts.add(
            new InvalidContract(
                version.versionId(), UNDECLARED_FIELD, null, field.name(), null, field.type()));
        continue;
      }
      if (!stableIds.add(declaration.stableId())
          || !semanticKeys.add(declaration.semanticKey())) {
        throw new FormConflictException(
            "同一版本内 stableId 或 semanticKey 重复：" + field.name());
      }
      if (!KNOWN_TYPES.contains(field.type())) {
        invalidContracts.add(
            new InvalidContract(
                version.versionId(),
                UNKNOWN_FROZEN_TYPE,
                declaration.semanticKey(),
                field.name(),
                declaration.declaredType(),
                field.type()));
        continue;
      }
      if (!declaration.declaredType().equals(field.type())) {
        invalidContracts.add(
            new InvalidContract(
                version.versionId(),
                TYPE_DECLARATION_CONFLICT,
                declaration.semanticKey(),
                field.name(),
                declaration.declaredType(),
                field.type()));
        continue;
      }
      FormStorageSpec storage = field.storage();
      if (storage == null || storage.resolvedColumn() == null) {
        invalidContracts.add(
            new InvalidContract(
                version.versionId(),
                STORAGE_UNRESOLVED,
                declaration.semanticKey(),
                field.name(),
                declaration.declaredType(),
                field.type()));
        continue;
      }
      Domain domain = domainOf(version.spec(), field);
      if (domain == null) {
        invalidContracts.add(
            new InvalidContract(
                version.versionId(),
                DOMAIN_UNRESOLVED,
                declaration.semanticKey(),
                field.name(),
                declaration.declaredType(),
                field.type()));
        continue;
      }
      String semanticHash =
          semanticHash(
              declaration.semanticKey(),
              declaration.meaning(),
              field.type(),
              declaration.unit(),
              1,
              domain.hash());
      FieldContract contract =
          new FieldContract(
              version.versionId(),
              declaration.stableId(),
              field.name(),
              field.apiKey(),
              declaration.semanticKey(),
              field.type(),
              declaration.unit(),
              1,
              domain.kind(),
              domain.hash(),
              semanticHash);
      if (contracts.putIfAbsent(declaration.stableId(), contract) != null) {
        throw new FormConflictException("同一版本内 stableId 重复：" + declaration.stableId());
      }
    }
    return contracts;
  }

  private Domain domainOf(FormTemplateSpec spec, FormFieldSpec field) {
    if (!"enum".equals(field.type()) && !"enum-array".equals(field.type())) {
      ObjectNode plain = objectMapper.createObjectNode();
      plain.put("kind", "plain");
      return new Domain("plain", hash(plain));
    }
    if (field.dictionary() != null && !field.dictionary().isBlank()) {
      FormDictionarySpec dictionary = spec.dictionary(field.dictionary()).orElse(null);
      if (dictionary == null) {
        return null;
      }
      List<String> values =
          dictionary.items().stream()
              .map(item -> item.key())
              .filter(Objects::nonNull)
              .distinct()
              .sorted()
              .toList();
      if (values.isEmpty()) {
        return null;
      }
      ObjectNode node = objectMapper.createObjectNode();
      node.put("kind", "dictionary");
      node.put("key", dictionary.name());
      node.put("version", dictionary.version());
      String contentHash = dictionary.canonicalSha256();
      if (contentHash == null || contentHash.isBlank()) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("key", dictionary.name());
        content.put("version", dictionary.version());
        ArrayNode contentValues = content.putArray("values");
        values.forEach(contentValues::add);
        contentHash = hash(content);
      }
      node.put("contentHash", contentHash);
      ArrayNode array = node.putArray("values");
      values.forEach(array::add);
      return new Domain("dictionary", hash(node));
    }
    List<String> values =
        field.options() == null
            ? List.of()
            : field.options().stream()
                .map(FormFieldOption::value)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    if (values.isEmpty()) {
      return null;
    }
    ObjectNode node = objectMapper.createObjectNode();
    node.put("kind", "enum");
    ArrayNode array = node.putArray("values");
    values.forEach(array::add);
    return new Domain("enum", hash(node));
  }

  private String semanticHash(
      String semanticKey,
      String meaning,
      String valueType,
      String unit,
      int semanticVersion,
      String domainHash) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("semanticKey", semanticKey);
    node.put("meaning", meaning);
    node.put("valueType", valueType);
    node.put("unit", unit);
    node.put("semanticVersion", semanticVersion);
    node.put("domainHash", domainHash);
    return hash(node);
  }

  private String hash(ObjectNode node) {
    try {
      return FormHashSupport.sha256Hex(objectMapper.writeValueAsString(node));
    } catch (Exception error) {
      throw new IllegalStateException("共同字段语义合同无法规范化", error);
    }
  }

  private FieldContract findVersionContract(
      Selection selection, long versionId, String semanticKey) {
    List<FieldContract> contracts = selection.perVersion().get(versionId);
    if (contracts == null) {
      return null;
    }
    return contracts.stream()
        .filter(contract -> semanticKey.equals(contract.semanticKey()))
        .findFirst()
        .orElse(null);
  }

  private String storageColumn(FormFieldSpec field) {
    if (field == null || field.storage() == null) {
      return null;
    }
    return field.storage().resolvedColumn();
  }

  private Long integerValue(Object raw) {
    if (raw instanceof Byte
        || raw instanceof Short
        || raw instanceof Integer
        || raw instanceof Long) {
      return ((Number) raw).longValue();
    }
    if (raw instanceof java.math.BigInteger value) {
      try {
        return value.longValueExact();
      } catch (ArithmeticException error) {
        return null;
      }
    }
    if (raw instanceof java.math.BigDecimal value) {
      try {
        return value.longValueExact();
      } catch (ArithmeticException error) {
        return null;
      }
    }
    return null;
  }

  private MetricResult emptyMetric(
      String status, String semanticKey, String invalidReason, List<Long> selectedVersions) {
    return new MetricResult(
        status, semanticKey, null, List.of(), List.of(), invalidReason, 0, 0, 0, 0, 0, 0, 0,
        selectedVersions);
  }

  private record Domain(String kind, String hash) {}
}
