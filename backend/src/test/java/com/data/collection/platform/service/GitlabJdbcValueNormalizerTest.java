package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class GitlabJdbcValueNormalizerTest {
  private final GitlabJdbcValueNormalizer normalizer = new GitlabJdbcValueNormalizer();

  @Test
  void shouldNormalizeSqlArrayValuesToDetachedJavaList() throws Exception {
    StubSqlArray sqlArray = new StubSqlArray(new Object[] {"a", 1L, new Object[] {"x", "y"}});

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
  void shouldNormalizeTimestampLikeValuesToUtcLocalDateTime() {
    java.sql.Timestamp timestamp = java.sql.Timestamp.from(Instant.parse("2026-05-28T10:00:00Z"));
    OffsetDateTime offsetDateTime = OffsetDateTime.of(2026, 5, 28, 18, 0, 0, 0, ZoneOffset.ofHours(8));

    assertThat(normalizer.normalize(timestamp)).isEqualTo(LocalDateTime.of(2026, 5, 28, 10, 0));
    assertThat(normalizer.normalize(offsetDateTime)).isEqualTo(LocalDateTime.of(2026, 5, 28, 10, 0));
  }

  private static final class StubSqlArray implements java.sql.Array {
    private final Object value;
    private boolean freed;

    private StubSqlArray(Object value) {
      this.value = value;
    }

    @Override
    public String getBaseTypeName() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getBaseType() throws SQLException {
      throw new UnsupportedOperationException();
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
