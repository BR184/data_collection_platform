package com.data.collection.platform.domain.form;

import java.util.List;

/** 版本表约束契约；启动校验按名称、类型、定义及外键执行属性逐项核对真实结构。 */
public record FormVersionConstraint(
    String tableName,
    String constraintName,
    Kind kind,
    List<String> columns,
    String referenceTable,
    List<String> referenceColumns,
    String expression,
    String onDelete,
    String onUpdate,
    String matchType,
    boolean deferrable,
    boolean initiallyDeferred,
    boolean validated) {

  public enum Kind {
    PRIMARY_KEY,
    UNIQUE,
    FOREIGN_KEY,
    CHECK
  }

  public static FormVersionConstraint primaryKey(
      String tableName, String constraintName, List<String> columns) {
    return withoutReference(tableName, constraintName, Kind.PRIMARY_KEY, columns);
  }

  public static FormVersionConstraint unique(
      String tableName, String constraintName, List<String> columns) {
    return withoutReference(tableName, constraintName, Kind.UNIQUE, columns);
  }

  public static FormVersionConstraint foreignKey(
      String tableName,
      String constraintName,
      List<String> columns,
      String referenceTable,
      List<String> referenceColumns) {
    return new FormVersionConstraint(
        tableName,
        constraintName,
        Kind.FOREIGN_KEY,
        columns,
        referenceTable,
        referenceColumns,
        null,
        "NO ACTION",
        "NO ACTION",
        "SIMPLE",
        false,
        false,
        true);
  }

  public static FormVersionConstraint check(
      String tableName, String constraintName, String expression) {
    return new FormVersionConstraint(
        tableName,
        constraintName,
        Kind.CHECK,
        List.of(),
        null,
        List.of(),
        expression,
        null,
        null,
        null,
        false,
        false,
        true);
  }

  private static FormVersionConstraint withoutReference(
      String tableName, String constraintName, Kind kind, List<String> columns) {
    return new FormVersionConstraint(
        tableName,
        constraintName,
        kind,
        columns,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        false,
        false,
        true);
  }
}
