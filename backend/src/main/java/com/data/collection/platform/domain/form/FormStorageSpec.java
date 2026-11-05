package com.data.collection.platform.domain.form;

/**
 * 稳定字段到物理列的绑定。
 *
 * <p>人员字段使用 {@code idColumn} 与 {@code nameColumn} 双列；其余字段使用 {@code column}。
 */
public record FormStorageSpec(
    String column, String columnType, String idColumn, String nameColumn, String nameColumnType) {

  public String resolvedColumn() {
    return column != null ? column : idColumn;
  }

  public String resolvedColumnType() {
    return columnType;
  }

  public String resolvedNameColumnType() {
    return nameColumnType != null ? nameColumnType : columnType;
  }
}
