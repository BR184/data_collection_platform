package com.data.collection.platform.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DashboardVersionScopeConfigLoaderTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shippedResourceDefinesTheCombinedDashboardScope() {
    DashboardVersionScopeConfigLoader loader =
        new DashboardVersionScopeConfigLoader(objectMapper);

    assertThat(loader.resourceHash()).hasSize(64);
    assertThat(loader.config().schemaVersion()).isEqualTo(1);
    assertThat(loader.config().scopes()).singleElement().satisfies(scope -> {
      assertThat(scope.key()).isEqualTo("cc-2027-r1-r2");
      assertThat(scope.displayName()).isEqualTo("CC2027R1&R2");
      assertThat(scope.anchorBusinessKey()).isEqualTo("CC2027R1");
      assertThat(scope.memberBusinessKeys()).containsExactly("CC2027R1", "CC2027R2");
    });
  }

  @Test
  void rejectsDuplicateKeysAndDisplayNames() {
    assertRejected(config(
        scope("dup", "CC2027R1&R2", "CC2027R1", "[\"CC2027R1\",\"CC2027R2\"]"),
        scope("dup", "CC2026R1&R2", "CC2026R1", "[\"CC2026R1\",\"CC2026R2\"]")),
        "key 重复");
    assertRejected(config(
        scope("a", "CC2027R1&R2", "CC2027R1", "[\"CC2027R1\",\"CC2027R2\"]"),
        scope("b", "cc2027r1&r2", "CC2026R1", "[\"CC2026R1\",\"CC2026R2\"]")),
        "显示名重复");
  }

  @Test
  void rejectsAnchorOutsideMembersAndCrossScopeMembers() {
    assertRejected(config(
        scope("a", "CC2027R1&R2", "CC2027R1", "[\"CC2027R2\"]")),
        "锚点不属于成员");
    assertRejected(config(
        scope("a", "CC2027R1&R2", "CC2027R1", "[\"CC2027R1\",\"CC2027R2\"]"),
        scope("b", "CC2027R2&R3", "CC2027R2", "[\"CC2027R2\",\"CC2027R3\"]")),
        "复用或重复登记");
  }

  @Test
  void rejectsAnotherProjectAndUnknownSchemaVersion() {
    assertRejected(
        """
        {"schemaVersion":1,"scopes":[
          {"key":"a","displayName":"A","projectId":325,"dimension":"TESTING_PHASE",
           "anchorBusinessKey":"A1","memberBusinessKeys":["A1"],"reviewProjectNames":[],
           "integrationTestPhases":[]}
        ]}
        """,
        "不属于已核实的平台项目");
    assertRejected(
        """
        {"schemaVersion":2,"scopes":[]}
        """,
        "schemaVersion");
  }

  private void assertRejected(String content, String message) {
    assertThatThrownBy(() -> new DashboardVersionScopeConfigLoader(
            objectMapper, content.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(message);
  }

  private String config(String... scopes) {
    return "{\"schemaVersion\":1,\"scopes\":[" + String.join(",", scopes) + "]}";
  }

  private String scope(
      String key, String displayName, String anchor, String memberBusinessKeys) {
    return """
        {"key":"%s","displayName":"%s","projectId":9,"dimension":"TESTING_PHASE",
         "anchorBusinessKey":"%s","memberBusinessKeys":%s,"reviewProjectNames":[],
         "integrationTestPhases":[]}
        """.formatted(key, displayName, anchor, memberBusinessKeys);
  }
}
