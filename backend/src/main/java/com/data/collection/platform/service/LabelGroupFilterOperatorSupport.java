package com.data.collection.platform.service;

import java.util.List;

final class LabelGroupFilterOperatorSupport {
  private LabelGroupFilterOperatorSupport() {}

  static boolean isSetOperator(String operator) {
    return "eq".equals(operator)
        || "ne".equals(operator)
        || "intersects".equals(operator)
        || "notIntersects".equals(operator)
        || "containsAll".equals(operator)
        || "notContainsAll".equals(operator);
  }

  static String normalize(String operator) {
    if ("eq".equals(operator)) {
      return "intersects";
    }
    if ("ne".equals(operator)) {
      return "notIntersects";
    }
    return operator;
  }

  static boolean matches(List<String> actualValues, List<String> expectedValues, String operator) {
    List<String> safeActual = actualValues == null ? List.of() : actualValues;
    List<String> safeExpected = expectedValues == null ? List.of() : expectedValues;
    if (safeExpected.stream().noneMatch(value -> TextQuerySupport.trimToNull(value) != null)) {
      return false;
    }
    boolean intersects =
        safeActual.stream()
            .filter(value -> TextQuerySupport.trimToNull(value) != null)
            .anyMatch(
                actual ->
                    safeExpected.stream().anyMatch(expected -> equalsIgnoreCase(actual, expected)));
    boolean containsAll =
        safeExpected.stream()
            .filter(value -> TextQuerySupport.trimToNull(value) != null)
            .allMatch(
                expected ->
                    safeActual.stream().anyMatch(actual -> equalsIgnoreCase(actual, expected)));
    return switch (normalize(operator)) {
      case "notIntersects" -> !intersects;
      case "containsAll" -> containsAll;
      case "notContainsAll" -> !containsAll;
      default -> intersects;
    };
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = TextQuerySupport.trimToNull(left);
    String safeRight = TextQuerySupport.trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }
}
