package com.opspilot.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * T09 acceptance: "create -> owner, ... role management, ... last owner preservation, cross-tenant
 * invite misuse tests" (the membership-management half; invitation flows are {@link
 * InvitationHttpIntegrationTest}). Needs a real Postgres via Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrganizationHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;

  @Test
  void creatingAnOrganizationMakesTheCallerItsOwner() throws Exception {
    User creator = users.saveAndFlush(user("creator"));
    String slug = "acme-" + UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(creator))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Acme\", \"slug\": \"" + slug + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Acme"))
        .andExpect(jsonPath("$.slug").value(slug))
        .andExpect(jsonPath("$.myRole").value("OWNER"));

    assertThat(organizations.existsBySlug(slug)).isTrue();
    Organization org = organizations.findBySlug(slug).orElseThrow();
    assertThat(memberships.findByOrganizationIdAndUserId(org.getId(), creator.getId()))
        .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MembershipRole.OWNER));
  }

  @Test
  void creatingAnOrganizationWithATakenSlugIsAStateConflict() throws Exception {
    User first = users.saveAndFlush(user("first"));
    User second = users.saveAndFlush(user("second"));
    String slug = "taken-" + UUID.randomUUID();
    organizations.saveAndFlush(new Organization("Existing", slug, first.getId()));

    mockMvc
        .perform(
            post("/api/v1/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(second))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Duplicate\", \"slug\": \"" + slug + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
  }

  @Test
  void creatingAnOrganizationWithAnInvalidSlugIsAValidationError() throws Exception {
    User creator = users.saveAndFlush(user("invalid-slug"));

    mockMvc
        .perform(
            post("/api/v1/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(creator))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Acme\", \"slug\": \"Not A Slug!\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void nonMembersCannotReadAnOrganizationOrItsMembers() throws Exception {
    User owner = users.saveAndFlush(user("owner"));
    User outsider = users.saveAndFlush(user("outsider"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/members", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider)))
        .andExpect(status().isForbidden());
  }

  @Test
  void listsEveryMemberWithTheirRole() throws Exception {
    User owner = users.saveAndFlush(user("owner2"));
    User member = users.saveAndFlush(user("member2"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/members", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void adminCanPromoteAMemberButNotToOwner() throws Exception {
    User owner = users.saveAndFlush(user("owner3"));
    User admin = users.saveAndFlush(user("admin3"));
    User member = users.saveAndFlush(user("member3"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(org.getId(), admin.getId(), MembershipRole.ADMIN));
    Membership memberMembership =
        memberships.saveAndFlush(
            new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));

    mockMvc
        .perform(
            patch(
                    "/api/v1/organizations/{orgId}/members/{memberId}",
                    org.getId(),
                    memberMembership.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));

    mockMvc
        .perform(
            patch(
                    "/api/v1/organizations/{orgId}/members/{memberId}",
                    org.getId(),
                    memberMembership.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"OWNER\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void onlyAnOwnerCanGrantOwnerRole() throws Exception {
    User owner = users.saveAndFlush(user("owner4"));
    User member = users.saveAndFlush(user("member4"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Membership memberMembership =
        memberships.saveAndFlush(
            new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));

    mockMvc
        .perform(
            patch(
                    "/api/v1/organizations/{orgId}/members/{memberId}",
                    org.getId(),
                    memberMembership.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"OWNER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("OWNER"));
  }

  @Test
  void theLastOwnerCannotBeDemoted() throws Exception {
    User owner = users.saveAndFlush(user("owner5"));
    Organization org = organizations.saveAndFlush(organization(owner));
    Membership ownerMembership =
        memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));

    mockMvc
        .perform(
            patch(
                    "/api/v1/organizations/{orgId}/members/{memberId}",
                    org.getId(),
                    ownerMembership.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"ADMIN\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));

    assertThat(memberships.findById(ownerMembership.getId()))
        .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MembershipRole.OWNER));
  }

  @Test
  void demotingAnOwnerIsFineWhenAnotherOwnerRemains() throws Exception {
    User owner = users.saveAndFlush(user("owner6"));
    User coOwner = users.saveAndFlush(user("coowner6"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Membership coOwnerMembership =
        memberships.saveAndFlush(
            new Membership(org.getId(), coOwner.getId(), MembershipRole.OWNER));

    mockMvc
        .perform(
            patch(
                    "/api/v1/organizations/{orgId}/members/{memberId}",
                    org.getId(),
                    coOwnerMembership.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));
  }

  @Test
  void aMembershipIdFromAnotherOrganizationIsConcealedAsNotFound() throws Exception {
    User ownerA = users.saveAndFlush(user("ownerA"));
    User ownerB = users.saveAndFlush(user("ownerB"));
    User memberB = users.saveAndFlush(user("memberB"));
    Organization orgA = organizations.saveAndFlush(organization(ownerA));
    Organization orgB = organizations.saveAndFlush(organization(ownerB));
    memberships.saveAndFlush(new Membership(orgA.getId(), ownerA.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(orgB.getId(), ownerB.getId(), MembershipRole.OWNER));
    Membership membershipInOrgB =
        memberships.saveAndFlush(
            new Membership(orgB.getId(), memberB.getId(), MembershipRole.MEMBER));

    // ownerA is a real ADMIN-or-above caller, but on THEIR OWN org — the membership id they
    // guess at belongs to org B, not org A.
    mockMvc
        .perform(
            patch(
                    "/api/v1/organizations/{orgId}/members/{memberId}",
                    orgA.getId(),
                    membershipInOrgB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ownerA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"ADMIN\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
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
