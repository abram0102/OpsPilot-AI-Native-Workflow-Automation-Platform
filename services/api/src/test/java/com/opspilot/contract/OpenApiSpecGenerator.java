package com.opspilot.contract;

import com.opspilot.TestcontainersConfiguration;
import java.nio.file.Files;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * T11: "Generate OpenAPI from actual Spring endpoints; commit spec and client with reproducible
 * generator command." Fetches the live spec from the real, running application (Testcontainers
 * Postgres included) and overwrites {@code packages/contracts/openapi.json} — never run by a bare
 * {@code mvn test} (see {@link OpenApiSpecs}'s javadoc for why this class isn't named {@code
 * ...Test}). Run explicitly:
 *
 * <pre>{@code
 * cd services/api && ./mvnw test -Dtest=OpenApiSpecGenerator
 * }</pre>
 *
 * (wired to {@code make generate-contracts}). Review the diff and commit it — {@link
 * OpenApiSpecDriftTest} is what then keeps CI honest about it never silently going stale.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecGenerator {

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void generate() throws Exception {
    String rawSpec = OpenApiSpecs.fetchLiveSpec(mockMvc, testIssuerKeyPair);
    Object tree = objectMapper.readValue(rawSpec, Object.class);
    String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    Files.createDirectories(OpenApiSpecs.OPENAPI_JSON_PATH.getParent());
    Files.writeString(OpenApiSpecs.OPENAPI_JSON_PATH, pretty + "\n");
  }
}
