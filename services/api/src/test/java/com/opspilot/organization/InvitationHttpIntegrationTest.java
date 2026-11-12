package com.opspilot.organization;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * T09 acceptance: "second user accept, ... token expiration, replay prevention, ... cross-tenant
 * invite misuse tests" (the invitation half; membership-role flows are {@link
 * OrganizationHttpIntegrationTest}). Needs a real Postgres via Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("security")
class InvitationHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private InvitationRepository invitations;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void adminInvitesASecondUserWhoAccepts() throws Exception {
    User admin = users.saveAndFlush(user("admin"));
    Organization org = organizations.saveAndFlush(organization(admin));
    memberships.saveAndFlush(new Membership(org.getId(), admin.getId(), MembershipRole.ADMIN));
    User invitee = users.saveAndFlush(user("invitee"));

    String token = createInvitation(org.getId(), admin, invitee.getEmail(), MembershipRole.MEMBER);

    mockMvc
        .perform(
            post("/api/v1/invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(invitee))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationId").value(org.getId().toString()))
        .andExpect(jsonPath("$.role").value("MEMBER"));

    assertThat(memberships.findByOrganizationIdAndUserId(org.getId(), invitee.getId()))
        .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MembershipRole.MEMBER));
  }

  @Test
  void aMemberCannotCreateInvitations() throws Exception {
    User owner = users.saveAndFlush(user("owner-inv"));
    User member = users.saveAndFlush(user("member-inv"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/invitations", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"nobody@example.com\", \"role\": \"MEMBER\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAdminCannotInviteAnOwnerButAnOwnerCan() throws Exception {
    User owner = users.saveAndFlush(user("owner-inv2"));
    User admin = users.saveAndFlush(user("admin-inv2"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(org.getId(), admin.getId(), MembershipRole.ADMIN));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/invitations", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"future-owner@example.com\", \"role\": \"OWNER\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/invitations", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"future-owner@example.com\", \"role\": \"OWNER\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void creatingAnInvitationForAnOrganizationYouDoNotBelongToIsDenied() throws Exception {
    User ownerA = users.saveAndFlush(user("ownerA-inv"));
    User outsider = users.saveAndFlush(user("outsider-inv"));
    Organization orgA = organizations.saveAndFlush(organization(ownerA));
    memberships.saveAndFlush(new Membership(orgA.getId(), ownerA.getId(), MembershipRole.OWNER));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/invitations", orgA.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"whoever@example.com\", \"role\": \"MEMBER\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void acceptingWithAnUnknownTokenIsNotFound() throws Exception {
    User someone = users.saveAndFlush(user("unknown-token"));

    mockMvc
        .perform(
            post("/api/v1/invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(someone))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"not-a-real-token\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void acceptingSomeoneElsesInvitationByEmailIsConcealedAsNotFound() throws Exception {
    User admin = users.saveAndFlush(user("admin-mismatch"));
    Organization org = organizations.saveAndFlush(organization(admin));
    memberships.saveAndFlush(new Membership(org.getId(), admin.getId(), MembershipRole.ADMIN));
    User wrongUser = users.saveAndFlush(user("wrong-user"));

    String token =
        createInvitation(
            org.getId(), admin, "intended-recipient@example.com", MembershipRole.MEMBER);

    mockMvc
        .perform(
            post("/api/v1/invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(wrongUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void anExpiredInvitationIsRejected() throws Exception {
    User admin = users.saveAndFlush(user("admin-expired"));
    Organization org = organizations.saveAndFlush(organization(admin));
    memberships.saveAndFlush(new Membership(org.getId(), admin.getId(), MembershipRole.ADMIN));
    User invitee = users.saveAndFlush(user("invitee-expired"));

    String rawToken = InvitationTokens.generate();
    invitations.saveAndFlush(
        new Invitation(
            org.getId(),
            invitee.getEmail(),
            MembershipRole.MEMBER,
            InvitationTokens.hash(rawToken),
            Instant.now().minusSeconds(60),
            admin.getId()));

    mockMvc
        .perform(
            post("/api/v1/invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(invitee))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + rawToken + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
  }

  @Test
  void anAlreadyAcceptedInvitationCannotBeReplayed() throws Exception {
    User admin = users.saveAndFlush(user("admin-replay"));
    Organization org = organizations.saveAndFlush(organization(admin));
    memberships.saveAndFlush(new Membership(org.getId(), admin.getId(), MembershipRole.ADMIN));
    User invitee = users.saveAndFlush(user("invitee-replay"));

    String token = createInvitation(org.getId(), admin, invitee.getEmail(), MembershipRole.MEMBER);

    mockMvc
        .perform(
            post("/api/v1/invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(invitee))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"))
        .andExpect(status().isOk());

    // Replay: same token, same (now-member) user.
    mockMvc
        .perform(
            post("/api/v1/invitations/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(invitee))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));

    assertThat(memberships.findAllByOrganizationId(org.getId())).hasSize(2);
  }

  private String createInvitation(UUID orgId, User asAdmin, String email, MembershipRole role)
      throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/organizations/{orgId}/invitations", orgId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(asAdmin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\": \"" + email + "\", \"role\": \"" + role + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(body, InvitationResponse.class).token();
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
