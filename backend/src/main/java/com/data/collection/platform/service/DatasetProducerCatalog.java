package com.data.collection.platform.service;

import com.data.collection.platform.entity.read.DatasetRelationInput;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.service.read.DatasetInputEvidence;
import com.data.collection.platform.service.read.DatasetPublicationRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 完整本地数据集的拥有者目录；范围来自实际装载配置，客户端不能选择关系或降低证据集合。 */
@Service
public class DatasetProducerCatalog {
  private final CodeReviewMatchModeConfigService settings;

  public DatasetProducerCatalog(CodeReviewMatchModeConfigService settings) { this.settings = settings; }

  /** 本地拥有者的初始接管顺序：先准备外部目标数据集，再公开包含模式切换的正式输入。 */
  public static List<ReadPublicationProducerKind> localDatasetOwners() {
    return List.of(ReadPublicationProducerKind.COMPAT_REVIEW, ReadPublicationProducerKind.COMPAT_WALKTHROUGH,
        ReadPublicationProducerKind.FORMAL_RECORD);
  }

  /** 生成当前生产请求；正式数据取完整已提交关系，兼容数据取具体来源专有装载清单。 */
  public DatasetPublicationRequest request(ReadPublicationProducerKind owner, String runId) {
    ReadPublicationDomainKey domain = new ReadPublicationDomainKey(owner, "default", "DATASET", datasetKey(owner));
    return switch (owner) {
      case FORMAL_RECORD -> new DatasetPublicationRequest(domain, runId, formalRelations(),
          new DatasetInputEvidence.FormalCommit());
      case COMPAT_WALKTHROUGH -> {
        var config = settings.loadConfig();
        List<String> sources = new ArrayList<>(List.of("cc"));
        if (org.springframework.util.StringUtils.hasText(config.dgmMysqlJdbcUrl())
            && !config.dgmMysqlJdbcUrl().equalsIgnoreCase(config.mysqlJdbcUrl())) { sources.add("dgm"); }
        List<String> required = sources.stream().map(source -> source + "." + config.mysqlTableName()).toList();
        yield new DatasetPublicationRequest(domain, runId,
            List.of(relation("code_review_match_mode_records", List.of("source_instance", "legacy_source_id"))),
            new DatasetInputEvidence.CompatibilityInventory(DatasetInputEvidence.Family.MYSQL_WALKTHROUGH, required));
      }
      case COMPAT_REVIEW -> {
        var config = settings.loadConfig();
        // 专属报告/问题/描述共同支撑列表与展开；缺少任一来源不能以空表认证。
        List<String> required = List.of(config.reviewReportCollectionName(), config.reviewProblemCollectionName(), "description");
        yield new DatasetPublicationRequest(domain, runId,
            List.of(relation("review_data_match_mode_reports", List.of("id")),
                relation("review_data_match_mode_problem_details", List.of("id")),
                relation("review_data_match_mode_descriptions", List.of("id")),
                relation("review_data_match_mode_contents", List.of("id")),
                relation("review_data_match_mode_report_description_refs", List.of("report_id", "description_legacy_id")),
                relation("review_data_match_mode_report_problem_refs", List.of("report_id", "problem_legacy_id"))),
            new DatasetInputEvidence.CompatibilityInventory(DatasetInputEvidence.Family.MONGO_REVIEW, required));
      }
      default -> throw new IllegalArgumentException("该拥有者必须使用专属完整输入生产入口：" + owner);
    };
  }

  private static List<DatasetRelationInput> formalRelations() {
    List<DatasetRelationInput> relations = new ArrayList<>();
    for (String table : List.of("review_records", "review_record_experts", "review_problem_items",
        "review_record_descriptions", "review_record_contents", "review_data_match_mode_edit_links",
        "review_data_match_mode_problem_edit_links", "code_review_external_metrics", "gitlab_mirror_records")) {
      relations.add(relation(table, List.of("id")));
    }
    // 只保存影响读取语义的设置，连接地址、用户名和密码不进入读版本。
    relations.add(new DatasetRelationInput("code_review_match_mode_db_settings", "code_review_match_mode_db_settings",
        List.of("id"), List.of("updated_at"),
        "select id, enabled, code_review_read_mode, review_data_read_mode, updated_at from code_review_match_mode_db_settings", List.of()));
    return List.copyOf(relations);
  }

  private static DatasetRelationInput relation(String table, List<String> keys) {
    return new DatasetRelationInput(table, table, keys, List.of("synced_at"), "select * from " + table, List.of());
  }

  /** 已归完整数据集的真实关系拥有者；数据库浏览不能把这些表当成当前原表读取。 */
  public static java.util.Optional<ReadPublicationDomainKey> domainForRelation(String tableName) {
    ReadPublicationProducerKind owner = null;
    if (formalRelations().stream().anyMatch(relation -> relation.relationName().equals(tableName))) {
      owner = ReadPublicationProducerKind.FORMAL_RECORD;
    } else if ("code_review_match_mode_records".equals(tableName)) {
      owner = ReadPublicationProducerKind.COMPAT_WALKTHROUGH;
    } else if (List.of("review_data_match_mode_reports", "review_data_match_mode_problem_details",
        "review_data_match_mode_descriptions", "review_data_match_mode_contents",
        "review_data_match_mode_report_description_refs", "review_data_match_mode_report_problem_refs").contains(tableName)) {
      owner = ReadPublicationProducerKind.COMPAT_REVIEW;
    }
    return owner == null ? java.util.Optional.empty()
        : java.util.Optional.of(new ReadPublicationDomainKey(owner, "default", "DATASET", datasetKey(owner)));
  }

  /** 数据集拥有者的稳定域内键，同时避免不同拥有者在读取组合中成为无法区分的同名输入。 */
  public static String datasetKey(ReadPublicationProducerKind owner) {
    return switch (owner) {
      case FORMAL_RECORD -> "formal-records";
      case COMPAT_WALKTHROUGH -> "compat-walkthrough";
      case COMPAT_REVIEW -> "compat-review";
      default -> throw new IllegalArgumentException("该拥有者没有公共数据集键");
    };
  }
}
