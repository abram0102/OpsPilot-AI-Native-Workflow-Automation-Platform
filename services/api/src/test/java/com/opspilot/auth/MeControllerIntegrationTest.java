package com.opspilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.MembershipRole;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * T08 acceptance: "Provision internal user by stable OIDC sub on first trusted login" and the
 * {@code GET /api/v1/me} contract (docs/API_CONTRACT.md). Needs a real Postgres via Testcontainers
 * because {@code /api/v1/me} provisions through the real {@link UserRepository}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MeControllerIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;

  @Test
  void firstLoginProvisionsUserFromVerifiedProfileClaimsAndIsIdempotent() throws Exception {
    String subject = "auth0|" + UUID.randomUUID();
    String token =
        tokenFor(
            subject,
            Map.of(
                UserProvisioningService.EMAIL_CLAIM, "ada@example.com",
                UserProvisioningService.NAME_CLAIM, "Ada Lovelace"));

    mockMvc
        .perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("ada@example.com"))
        .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
        .andExpect(jsonPath("$.organizations").isArray())
        .andExpect(jsonPath("$.organizations").isEmpty());

    assertThat(users.findByAuthSubject(subject)).isPresent();
    long countAfterFirstCall = users.count();

    // Second call for the same subject must not create a second row.
    mockMvc
        .perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("ada@example.com"));
    assertThat(users.count()).isEqualTo(countAfterFirstCall);
  }

  @Test
  void firstLoginWithoutProfileClaimsFallsBackToASyntheticPlaceholderInsteadOfFailing()
      throws Exception {
    String subject = "test-issuer|" + UUID.randomUUID();
    String token = tokenFor(subject, Map.of());

    mockMvc
        .perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(endsWith("@users.opspilot.local")))
        .andExpect(jsonPath("$.displayName").isNotEmpty());
  }

  @Test
  void returnsEveryOrganizationTheUserBelongsToWithItsRole() throws Exception {
    User user =
        users.saveAndFlush(new User("auth0|" + UUID.randomUUID(), "owner@example.com", "Owner"));
    Organization org =
        organizations.saveAndFlush(
            new Organization("Acme", "acme-" + UUID.randomUUID(), user.getId()));
    memberships.saveAndFlush(new Membership(org.getId(), user.getId(), MembershipRole.OWNER));

    String token = tokenFor(user.getAuthSubject(), Map.of());

    mockMvc
        .perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizations[0].organizationId").value(org.getId().toString()))
        .andExpect(jsonPath("$.organizations[0].name").value("Acme"))
        .andExpect(jsonPath("$.organizations[0].role").value("OWNER"));
  }

  private String tokenFor(String subject, Map<String, Object> extraClaims) {
    return TestJwtSigner.sign(
        testIssuerKeyPair,
        TestIssuerConfig.TEST_ISSUER,
        subject,
        AUDIENCE,
        Instant.now().plusSeconds(60),
        extraClaims);
  }
}
