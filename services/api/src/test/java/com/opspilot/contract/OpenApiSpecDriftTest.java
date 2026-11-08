package com.opspilot.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T11 acceptance: "CI drift check fails when generated spec/client out of sync." Compares the spec
 * the running application actually serves right now against the committed {@code
 * packages/contracts/openapi.json} — read-only, never regenerates it (that's {@link
 * OpenApiSpecGenerator}'s job). Parses both into a {@link JsonNode} tree rather than comparing raw
 * strings, so formatting/key-order differences never produce a false positive. Needs a real
 * Postgres via Testcontainers (springdoc's own schema introspection needs the full context).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecDriftTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void committedSpecMatchesWhatTheApplicationActuallyServes() throws Exception {
    String liveSpec = OpenApiSpecs.fetchLiveSpec(mockMvc, testIssuerKeyPair);
    JsonNode live = objectMapper.readTree(liveSpec);

    String committedSpec;
    try {
      committedSpec = Files.readString(OpenApiSpecs.OPENAPI_JSON_PATH);
    } catch (IOException e) {
      throw new AssertionError(
          "packages/contracts/openapi.json is missing. Run `make generate-contracts` and commit"
              + " the result.",
          e);
    }
    JsonNode committed = objectMapper.readTree(committedSpec);

    assertThat(committed)
        .as(
            "packages/contracts/openapi.json is out of date relative to the real endpoints. Run"
                + " `make generate-contracts`, review the diff, and commit it.")
        .isEqualTo(live);
  }
}
