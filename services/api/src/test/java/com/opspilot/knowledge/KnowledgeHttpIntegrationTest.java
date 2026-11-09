package com.opspilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.outbox.OutboxEventRepository;
import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.MembershipRole;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * T13 acceptance: "upload TXT/MD, reject 11 MiB file or unsupported executable, reject forged
 * cross-tenant doc, never path-traverse, file persisted; upload complete transitions to QUEUED
 * transactionally." Drives the real local-adapter end to end over HTTP. Needs a real Postgres via
 * Testcontainers; {@code storage.local-dir} is redirected to a JUnit temp directory so tests never
 * touch this module's real {@code .local-storage}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KnowledgeHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @TempDir private static Path storageDir;

  @DynamicPropertySource
  static void storageProperties(DynamicPropertyRegistry registry) {
    registry.add("storage.local-dir", () -> storageDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private KnowledgeDocumentRepository documents;
  @Autowired private OutboxEventRepository outboxEvents;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void aMemberCanUploadATxtFileAndACompleteTransitionsItToQueued() throws Exception {
    User member = users.saveAndFlush(user("member-upload"));
    Organization org = organizations.saveAndFlush(organization(member));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));

    byte[] content = "Hello, knowledge base.".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256Hex(content);

    String sessionBody =
        mockMvc
            .perform(
                post("/api/v1/organizations/{orgId}/knowledge/uploads", org.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"filename\":\"notes.txt\",\"mimeType\":\"text/plain\",\"sizeBytes\":"
                            + content.length
                            + ",\"sha256\":\""
                            + sha256
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    CreateUploadSessionResponse session =
        objectMapper.readValue(sessionBody, CreateUploadSessionResponse.class);

    mockMvc
        .perform(
            put(
                    "/api/v1/organizations/{orgId}/knowledge/uploads/local-object?objectKey={key}",
                    org.getId(),
                    session.objectKey())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(content))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}/complete",
                    org.getId(),
                    session.documentId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("QUEUED"));

    mockMvc
        .perform(
            get(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}",
                    org.getId(),
                    session.documentId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.filename").value("notes.txt"));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/knowledge/documents", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    assertThat(
            outboxEvents.countByAggregateTypeAndAggregateId(
                "knowledge_document", session.documentId()))
        .isEqualTo(1);
  }

  @Test
  void aViewerCannotCreateAnUploadSession() throws Exception {
    User owner = users.saveAndFlush(user("owner-kb"));
    User viewer = users.saveAndFlush(user("viewer-kb"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(org.getId(), viewer.getId(), MembershipRole.VIEWER));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/knowledge/uploads", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"filename\":\"a.txt\",\"mimeType\":\"text/plain\",\"sizeBytes\":4,\"sha256\":\""
                        + "a".repeat(64)
                        + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anUnsupportedExecutableExtensionIsRejectedAtSessionCreation() throws Exception {
    User owner = users.saveAndFlush(user("owner-exe"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/knowledge/uploads", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"filename\":\"virus.exe\",\"mimeType\":\"application/octet-stream\",\"sizeBytes\":4,\"sha256\":\""
                        + "a".repeat(64)
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void anOversizeFileIsRejectedAtSessionCreation() throws Exception {
    User owner = users.saveAndFlush(user("owner-big"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    long elevenMib = 11L * 1024 * 1024;

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/knowledge/uploads", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"filename\":\"big.txt\",\"mimeType\":\"text/plain\",\"sizeBytes\":"
                        + elevenMib
                        + ",\"sha256\":\""
                        + "a".repeat(64)
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void uploadingContentThatLooksLikeAnExecutableIsRejectedAtTheByteReceivingStep()
      throws Exception {
    User owner = users.saveAndFlush(user("owner-magic"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));

    byte[] exeBytes = {'M', 'Z', 0, 0, 0, 0};
    String sessionBody =
        mockMvc
            .perform(
                post("/api/v1/organizations/{orgId}/knowledge/uploads", org.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"filename\":\"notes.txt\",\"mimeType\":\"text/plain\",\"sizeBytes\":"
                            + exeBytes.length
                            + ",\"sha256\":\""
                            + sha256Hex(exeBytes)
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    CreateUploadSessionResponse session =
        objectMapper.readValue(sessionBody, CreateUploadSessionResponse.class);

    mockMvc
        .perform(
            put(
                    "/api/v1/organizations/{orgId}/knowledge/uploads/local-object?objectKey={key}",
                    org.getId(),
                    session.objectKey())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(exeBytes))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void completingBeforeTheBytesAreUploadedIsRejected() throws Exception {
    User owner = users.saveAndFlush(user("owner-nobytes"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));

    String sessionBody =
        mockMvc
            .perform(
                post("/api/v1/organizations/{orgId}/knowledge/uploads", org.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"filename\":\"notes.txt\",\"mimeType\":\"text/plain\",\"sizeBytes\":5,\"sha256\":\""
                            + "a".repeat(64)
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    CreateUploadSessionResponse session =
        objectMapper.readValue(sessionBody, CreateUploadSessionResponse.class);

    mockMvc
        .perform(
            post(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}/complete",
                    org.getId(),
                    session.documentId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
  }

  @Test
  void duplicateCompletionIsSafeAndDoesNotDuplicateTheOutboxEvent() throws Exception {
    User owner = users.saveAndFlush(user("owner-dup"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    byte[] content = "dup".getBytes(StandardCharsets.UTF_8);

    String sessionBody =
        mockMvc
            .perform(
                post("/api/v1/organizations/{orgId}/knowledge/uploads", org.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"filename\":\"dup.md\",\"mimeType\":\"text/plain\",\"sizeBytes\":"
                            + content.length
                            + ",\"sha256\":\""
                            + sha256Hex(content)
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    CreateUploadSessionResponse session =
        objectMapper.readValue(sessionBody, CreateUploadSessionResponse.class);

    mockMvc
        .perform(
            put(
                    "/api/v1/organizations/{orgId}/knowledge/uploads/local-object?objectKey={key}",
                    org.getId(),
                    session.objectKey())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(content))
        .andExpect(status().isNoContent());

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              post(
                      "/api/v1/organizations/{orgId}/knowledge/documents/{docId}/complete",
                      org.getId(),
                      session.documentId())
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    assertThat(
            outboxEvents.countByAggregateTypeAndAggregateId(
                "knowledge_document", session.documentId()))
        .isEqualTo(1);
  }

  @Test
  void aForgedCrossTenantDocumentIdIsConcealedAsNotFound() throws Exception {
    User ownerA = users.saveAndFlush(user("ownerA-kb"));
    User ownerB = users.saveAndFlush(user("ownerB-kb"));
    Organization orgA = organizations.saveAndFlush(organization(ownerA));
    Organization orgB = organizations.saveAndFlush(organization(ownerB));
    memberships.saveAndFlush(new Membership(orgA.getId(), ownerA.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(orgB.getId(), ownerB.getId(), MembershipRole.OWNER));

    KnowledgeDocument docInOrgB =
        documents.saveAndFlush(
            new KnowledgeDocument(
                orgB.getId(),
                ownerB.getId(),
                "secret.txt",
                "text/plain",
                "orgs/x/knowledge/y",
                "a".repeat(64),
                4));

    mockMvc
        .perform(
            get(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}",
                    orgA.getId(),
                    docInOrgB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ownerA)))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            post(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}/complete",
                    orgA.getId(),
                    docInOrgB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ownerA)))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            delete(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}",
                    orgA.getId(),
                    docInOrgB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ownerA)))
        .andExpect(status().isNotFound());
  }

  @Test
  void onlyAnAdminCanDeleteAndTheDocumentIsGoneAfterwards() throws Exception {
    User owner = users.saveAndFlush(user("owner-del"));
    User member = users.saveAndFlush(user("member-del"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));
    KnowledgeDocument doc =
        documents.saveAndFlush(
            new KnowledgeDocument(
                org.getId(),
                owner.getId(),
                "a.txt",
                "text/plain",
                "orgs/x/knowledge/z",
                "a".repeat(64),
                4));

    mockMvc
        .perform(
            delete(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}",
                    org.getId(),
                    doc.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            delete(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}",
                    org.getId(),
                    doc.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get(
                    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}",
                    org.getId(),
                    doc.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner)))
        .andExpect(status().isNotFound());
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

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
