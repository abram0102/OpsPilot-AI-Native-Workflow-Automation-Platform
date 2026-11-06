package com.opspilot.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** Needs a real Postgres via Testcontainers — see docs/PROGRESS.md T01/T02. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MembershipRepositoryIntegrationTest {

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;

  @Test
  void sameUserCanOnlyHaveOneRolePerOrganization() {
    User user =
        users.saveAndFlush(
            new User("auth|dup-" + UUID.randomUUID(), "dup@example.com", "Dup Test"));
    Organization org =
        organizations.saveAndFlush(
            new Organization("Dup Org", "dup-org-" + UUID.randomUUID(), user.getId()));
    memberships.saveAndFlush(new Membership(org.getId(), user.getId(), MembershipRole.OWNER));

    assertThatThrownBy(() ->
            memberships.saveAndFlush(
                new Membership(org.getId(), user.getId(), MembershipRole.MEMBER)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void membershipLooksUpByOrganizationAndUser() {
    User user =
        users.saveAndFlush(
            new User("auth|lookup-" + UUID.randomUUID(), "lookup@example.com", "Lookup Test"));
    Organization org =
        organizations.saveAndFlush(
            new Organization("Lookup Org", "lookup-org-" + UUID.randomUUID(), user.getId()));
    memberships.saveAndFlush(new Membership(org.getId(), user.getId(), MembershipRole.ADMIN));

    assertThat(memberships.findByOrganizationIdAndUserId(org.getId(), user.getId()))
        .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MembershipRole.ADMIN));
    assertThat(memberships.findByOrganizationIdAndUserId(UUID.randomUUID(), user.getId()))
        .isEmpty();
  }
}
