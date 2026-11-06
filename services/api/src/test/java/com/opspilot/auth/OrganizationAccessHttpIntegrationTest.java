package com.opspilot.auth;

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
 * T07 acceptance: "owner/member/viewer role tests prove server checks" and "API never trusts client
 * role claims". Drives {@link OrganizationAccessService} through real HTTP, with real seeded
 * users/memberships and real test-signed JWTs — the role actually comes from the {@code
 * memberships} row the server looks up, never from anything the client sends. Needs a real Postgres
 * via Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrganizationAccessHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;

  @Test
  void ownerPassesBothChecksMemberPassesOnlyMemberLevelViewerPassesNeither() throws Exception {
    User owner = users.saveAndFlush(user("owner"));
    User member = users.saveAndFlush(user("member"));
    User viewer = users.saveAndFlush(user("viewer"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));
    memberships.saveAndFlush(new Membership(org.getId(), viewer.getId(), MembershipRole.VIEWER));

    mockMvc
        .perform(adminAction(org.getId(), owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));
    mockMvc.perform(memberAction(org.getId(), owner)).andExpect(status().isOk());

    mockMvc.perform(memberAction(org.getId(), member)).andExpect(status().isOk());
    mockMvc
        .perform(adminAction(org.getId(), member))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

    mockMvc.perform(memberAction(org.getId(), viewer)).andExpect(status().isForbidden());
    mockMvc.perform(adminAction(org.getId(), viewer)).andExpect(status().isForbidden());
  }

  @Test
  void userWithNoMembershipInTheOrganizationIsDenied() throws Exception {
    User owner = users.saveAndFlush(user("owner2"));
    User outsider = users.saveAndFlush(user("outsider"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));

    mockMvc.perform(memberAction(org.getId(), outsider)).andExpect(status().isForbidden());
  }

  private org.springframework.test.web.servlet.RequestBuilder memberAction(UUID orgId, User as) {
    return get("/__test/organizations/{orgId}/member-action", orgId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(as));
  }

  private org.springframework.test.web.servlet.RequestBuilder adminAction(UUID orgId, User as) {
    return get("/__test/organizations/{orgId}/admin-action", orgId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(as));
  }

  private String tokenFor(User user) {
    return TestJwtSigner.sign(
        testIssuerKeyPair,
        TestIssuerConfig.TEST_ISSUER,
        user.getAuthSubject(),
        AUDIENCE,
        Instant.now().plusSeconds(60));
  }

  private static User user(String label) {
    String unique = UUID.randomUUID().toString();
    return new User("auth|" + label + "-" + unique, label + "-" + unique + "@example.com", label);
  }

  private static Organization organization(User owner) {
    return new Organization("Test Org", "test-org-" + UUID.randomUUID(), owner.getId());
  }
}
