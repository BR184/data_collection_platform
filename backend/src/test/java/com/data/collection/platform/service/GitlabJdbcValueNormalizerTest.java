package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.util.PGobject;

class GitlabJdbcValueNormalizerTest {
  private final GitlabJdbcValueNormalizer normalizer = new GitlabJdbcValueNormalizer();

  @Test
  void shouldNormalizeSqlArrayValuesToDetachedJavaList() throws Exception {
    StubSqlArray sqlArray =
        new StubSqlArray(
            new Object[] {"a", 1L, new Object[] {"x", "y"}},
            java.sql.Types.VARCHAR,
            "text");

    Object normalized = normalizer.normalize(sqlArray);

    assertThat(normalized).isInstanceOf(List.class);
    List<?> items = (List<?>) normalized;
    assertThat(items).hasSize(3);
    assertThat(items.get(0)).isEqualTo("a");
    assertThat(items.get(1)).isEqualTo(1L);
    assertThat(items.get(2)).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<Object> nested = (List<Object>) items.get(2);
    assertThat(nested).containsExactly("x", "y");
    assertThat(sqlArray.freed).isTrue();
  }

  @Test
  void shouldNormalizePostgresSpecificObjectsToRawValues() throws Exception {
    PGobject inet = new PGobject();
    inet.setType("inet");
    inet.setValue("172.18.0.1");
    PGobject searchVector = new PGobject();
    searchVector.setType("tsvector");
    searchVector.setValue("'sample':1 'vector':2");

    assertThat(normalizer.normalize(inet)).isEqualTo("172.18.0.1");
    assertThat(normalizer.normalize(searchVector)).isEqualTo("'sample':1 'vector':2");
  }

  @Test
  void shouldNormalizeSqlXmlValuesToDetachedString() throws Exception {
    StubSqlXml xml = new StubSqlXml("<root><value>ok</value></root>");

    Object normalized = normalizer.normalize(xml);

    assertThat(normalized).isEqualTo("<root><value>ok</value></root>");
    assertThat(xml.freed).isTrue();
  }

  @Test
  @ResourceLock("default-time-zone")
  void test_timestamp_without_time_zone_preserves_source_wall_clock() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
    try {
      LocalDateTime sourceValue = LocalDateTime.of(2026, 8, 6, 3, 20, 22, 840_104_000);
      java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf(sourceValue);

      assertThat(normalizer.normalize(timestamp)).isEqualTo(sourceValue);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  @ResourceLock("default-time-zone")
  void test_postgresql_timestamptz_normalizes_same_instant_to_utc() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
    try {
      java.time.Instant sourceInstant =
          java.time.Instant.parse("2026-08-06T03:20:22.840104Z");
      java.sql.Timestamp timestamp = java.sql.Timestamp.from(sourceInstant);

      assertThat(normalizer.normalize(timestamp, java.sql.Types.TIMESTAMP, "timestamptz"))
          .isEqualTo(LocalDateTime.ofInstant(sourceInstant, ZoneOffset.UTC));
      assertThat(
              normalizer.normalize(
                  timestamp,
                  java.sql.Types.TIMESTAMP,
                  "TIMESTAMP WITH TIME ZONE"))
          .isEqualTo(LocalDateTime.ofInstant(sourceInstant, ZoneOffset.UTC));
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  @ResourceLock("default-time-zone")
  void test_standard_timezone_jdbc_type_normalizes_same_instant_without_type_name() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
    try {
      java.time.Instant sourceInstant = java.time.Instant.parse("2026-08-06T03:20:22Z");

      assertThat(
              normalizer.normalize(
                  java.sql.Timestamp.from(sourceInstant),
                  java.sql.Types.TIMESTAMP_WITH_TIMEZONE,
                  null))
          .isEqualTo(LocalDateTime.ofInstant(sourceInstant, ZoneOffset.UTC));
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  @ResourceLock("default-time-zone")
  void test_timestamp_array_uses_array_base_type_semantics() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
    try {
      java.time.Instant sourceInstant = java.time.Instant.parse("2026-08-06T03:20:22Z");
      StubSqlArray sqlArray =
          new StubSqlArray(
              new Object[] {java.sql.Timestamp.from(sourceInstant)},
              java.sql.Types.TIMESTAMP,
              "timestamptz");

      assertThat(normalizer.normalize(sqlArray))
          .isEqualTo(List.of(LocalDateTime.ofInstant(sourceInstant, ZoneOffset.UTC)));
      assertThat(sqlArray.freed).isTrue();
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void test_offset_datetime_normalizes_same_instant_to_utc_local_datetime() {
    OffsetDateTime offsetDateTime = OffsetDateTime.of(2026, 5, 28, 18, 0, 0, 0, ZoneOffset.ofHours(8));

    assertThat(normalizer.normalize(offsetDateTime)).isEqualTo(LocalDateTime.of(2026, 5, 28, 10, 0));
  }

  private static final class StubSqlArray implements java.sql.Array {
    private final Object value;
    private final int baseType;
    private final String baseTypeName;
    private boolean freed;

    private StubSqlArray(Object value, int baseType, String baseTypeName) {
      this.value = value;
      this.baseType = baseType;
      this.baseTypeName = baseTypeName;
    }

    @Override
    public String getBaseTypeName() throws SQLException {
      return baseTypeName;
    }

    @Override
    public int getBaseType() throws SQLException {
      return baseType;
    }

    @Override
    public Object getArray() throws SQLException {
      return value;
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void free() throws SQLException {
      freed = true;
    }
  }

  private static final class StubSqlXml implements SQLXML {
    private final String value;
    private boolean freed;

    private StubSqlXml(String value) {
      this.value = value;
    }

    @Override
    public void free() throws SQLException {
      freed = true;
    }

    @Override
    public String getString() throws SQLException {
      return value;
    }

    @Override
    public void setString(String value) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.InputStream getBinaryStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.OutputStream setBinaryStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.Reader getCharacterStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.Writer setCharacterStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends javax.xml.transform.Source> T getSource(Class<T> sourceClass) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends javax.xml.transform.Result> T setResult(Class<T> resultClass) throws SQLException {
      throw new UnsupportedOperationException();
    }
  }
}
