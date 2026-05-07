package com.data.collection.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.data.collection.platform.entity.GitlabMirrorRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface GitlabMirrorRecordMapper extends BaseMapper<GitlabMirrorRecord> {
  @Insert({
      "<script>",
      "insert into gitlab_mirror_records(config_id, table_name, record_key, updated_at_source, row_data, synced_at, created_at)",
      "values",
      "<foreach collection='records' item='record' separator=','>",
      "(",
      "#{record.configId},",
      "#{record.tableName},",
      "#{record.recordKey},",
      "#{record.updatedAtSource},",
      "cast(#{record.rowData} as jsonb),",
      "current_timestamp,",
      "current_timestamp",
      ")",
      "</foreach>",
      "on conflict (config_id, table_name, record_key)",
      "do update set updated_at_source = excluded.updated_at_source,",
      "row_data = excluded.row_data,",
      "synced_at = current_timestamp",
      "where excluded.updated_at_source is null",
      "or gitlab_mirror_records.updated_at_source is null",
      "or excluded.updated_at_source >= gitlab_mirror_records.updated_at_source",
      "</script>"
  })
  void upsertBatch(@Param("records") List<GitlabMirrorRecord> records);
}
