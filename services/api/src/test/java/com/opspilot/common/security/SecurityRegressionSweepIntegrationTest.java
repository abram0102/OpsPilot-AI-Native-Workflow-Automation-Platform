package com.opspilot.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.MembershipRole;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import java.net.URL;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The authorization regression net for docs/TASKS.md T28 ("automated exploit attempts all
 * blocked").
 *
 * <p>Every other security test in this repository names the endpoint it attacks, which means it can
 * only ever prove something about an endpoint somebody remembered to write a test for. This one
 * does the opposite: it asks Spring for the complete set of mapped request handlers and attacks
 * <em>all</em> of them. A route added in a later ticket that forgets {@code
 * OrganizationAccessService} — or forgets to be authenticated at all — fails here without anyone
 * having to remember T28 existed.
 *
 * <p>Three separate claims, deliberately kept apart because they fail for different reasons:
 *
 * <ol>
 *   <li>no credential reaches any handler (every mapping answers 401),
 *   <li>a validly authenticated stranger reaches no tenant's data (every {@code {orgId}} mapping
 *       refuses them),
 *   <li>the test-only routes this very suite relies on are not in the shipped artifact.
 * </ol>
 *
 * <p>Each assertion carries the offending route in its message, since "one of 40 endpoints is
 * wrong" is not an actionable failure.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("security")
class SecurityRegressionSweepIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  /** Substituted for every path variable that is not the organization id. */
  private static final UUID SOME_ID = UUID.fromString("00000000-0000-4000-8000-00000000beef");

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  /** One concrete HTTP entry point: a method and a URI template. */
  private record Route(RequestMethod method, String pattern) implements Comparable<Route> {
    @Override
    public int compareTo(Route other) {
      return Comparator.comparing(Route::pattern)
          .thenComparing(route -> route.method().name())
          .compare(this, other);
    }

    @Override
    public String toString() {
      return method.name() + " " + pattern;
    }
  }

  @Test
  void everyMappedEndpointRefusesACallerWithNoCredential() throws Exception {
    List<Route> routes = mappedRoutes();

    // A sweep that discovered nothing would pass silently and prove the opposite of what it claims,
    // so the population itself is asserted. The number only has to be a floor that a broken
    // discovery (empty set, or one controller) cannot clear.
    assertThat(routes)
        .as("the handler mapping should expose the whole API surface")
        .hasSizeGreaterThan(20);

    Map<Route, Integer> admitted = new TreeMap<>();
    for (Route route : routes) {
      MvcResult result = mockMvc.perform(request(route, null)).andReturn();
      if (result.getResponse().getStatus() != 401) {
        admitted.put(route, result.getResponse().getStatus());
      }
    }

    assertThat(admitted)
        .as(
            "every mapped endpoint must answer 401 without a bearer token — these did not."
                + " The health probes are deliberately public, but they are actuator endpoints and"
                + " so are not in this mapping at all; anything listed here is a real gap.")
        .isEmpty();
  }

  @Test
  void theUnauthenticatedRefusalIsTheContractedEnvelopeRatherThanAnEmptyChallenge()
      throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  @Test
  void everyOrganizationScopedEndpointRefusesAValidlyAuthenticatedStranger() throws Exception {
    Organization org = seedOrganizationWithAnOwner();
    String strangerToken = tokenFor(users.saveAndFlush(user("stranger")));

    List<Route> scoped =
        mappedRoutes().stream().filter(route -> route.pattern().contains("{orgId}")).toList();
    assertThat(scoped)
        .as("every tenant-scoped route is keyed by {orgId}; finding none means discovery broke")
        .hasSizeGreaterThan(10);

    // Why GET is held to the stricter standard: a write route is given an empty JSON body here,
    // because inventing a valid body for an arbitrary discovered endpoint is not something a
    // generic sweep can do. Bean validation runs during argument resolution, so such a request can
    // legitimately be rejected as 400 before authorization is ever consulted. That is still a
    // refusal and still no leak, so writes are held to "4xx, never 2xx, never 5xx". A GET carries
    // no body, cannot be deflected by validation, and therefore must be refused by the
    // authorization check itself — 403 (you are not a member) or 404 (concealed as nonexistent).
    Map<Route, String> wrong = new LinkedHashMap<>();
    for (Route route : scoped) {
      MvcResult result = mockMvc.perform(request(route, strangerToken)).andReturn();
      int status = result.getResponse().getStatus();

      boolean acceptable =
          route.method() == RequestMethod.GET
              ? (status == 403 || status == 404)
              : (status >= 400 && status < 500);
      if (!acceptable) {
        wrong.put(route, status + " " + firstLineOf(result.getResponse().getContentAsString()));
      }
    }

    assertThat(wrong)
        .as(
            "a validly signed token for a user with no membership anywhere must not reach any"
                + " tenant-scoped handler. A 2xx means missing authorization; a 5xx means the"
                + " handler ran far enough to break, which is the same bug one stack frame later.")
        .isEmpty();
  }

  @Test
  void theOrganizationItselfIsStillReachableByItsOwnMember() throws Exception {
    // The sweep above would pass just as happily if authorization refused *everyone*, which would
    // be a broken product rather than a secure one. This is the control: the same route the
    // stranger was refused answers 200 for a real member, so "all blocked" above means "blocked for
    // the stranger", not "blocked for all".
    Organization org = seedOrganizationWithAnOwner();
    User owner = users.findById(org.getCreatedBy()).orElseThrow();

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/organizations/{orgId}", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner)))
        .andExpect(status().isOk());
  }

  @Test
  void noTestOnlyEndpointIsPartOfTheShippedApplication() {
    // T28 acceptance: "no publicly reachable test auth shortcut". The suite's own fixture
    // controllers are mapped under /__test/ and they are swept for 401 like everything else — but
    // the claim that matters is that they are not in the jar. Checked by where the declaring class
    // was actually loaded from, not by trusting the package name.
    List<String> shipped = new ArrayList<>();
    List<String> testOnly = new ArrayList<>();

    handlerMapping
        .getHandlerMethods()
        .forEach((info, handler) -> {
          Set<String> patterns = patternsOf(info);
          if (patterns.stream().noneMatch(pattern -> pattern.startsWith("/__test"))) {
            return;
          }
          Class<?> declaring = handler.getBeanType();
          String origin = codeSourceOf(declaring);
          if (origin.contains("test-classes")) {
            testOnly.add(declaring.getName());
          } else {
            shipped.add(declaring.getName() + " loaded from " + origin);
          }
        });

    assertThat(testOnly)
        .as("the fixture controllers should exist, otherwise this test proves nothing")
        .isNotEmpty();
    assertThat(shipped)
        .as("a /__test/ route declared by a class in the main artifact is a shipped auth shortcut")
        .isEmpty();
  }

  // --- discovery -------------------------------------------------------------------------------

  private List<Route> mappedRoutes() {
    List<Route> routes = new ArrayList<>();
    handlerMapping
        .getHandlerMethods()
        .forEach((info, handler) -> {
          Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
          for (String pattern : patternsOf(info)) {
            if (methods.isEmpty()) {
              // A mapping with no method condition answers every verb; GET is enough to prove the
              // filter chain rejects it.
              routes.add(new Route(RequestMethod.GET, pattern));
            } else {
              methods.forEach(method -> routes.add(new Route(method, pattern)));
            }
          }
        });
    return routes.stream().distinct().sorted().toList();
  }

  private static Set<String> patternsOf(RequestMappingInfo info) {
    return info.getPathPatternsCondition() == null
        ? Set.of()
        : info.getPathPatternsCondition().getPatternValues();
  }

  private static String codeSourceOf(Class<?> type) {
    URL location =
        type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null
            ? null
            : type.getProtectionDomain().getCodeSource().getLocation();
    return location == null ? "unknown" : location.toString();
  }

  // --- request building ------------------------------------------------------------------------

  private MockHttpServletRequestBuilder request(Route route, String bearerToken) {
    String uri = route.pattern().replace("{orgId}", organizationIdForSweep.toString());
    uri = uri.replaceAll("\\{[^}]+}", SOME_ID.toString());

    MockHttpServletRequestBuilder builder =
        switch (route.method()) {
          case POST -> MockMvcRequestBuilders.post(uri);
          case PUT -> MockMvcRequestBuilders.put(uri);
          case PATCH -> MockMvcRequestBuilders.patch(uri);
          case DELETE -> MockMvcRequestBuilders.delete(uri);
          default -> MockMvcRequestBuilders.get(uri);
        };

    if (route.method() == RequestMethod.POST
        || route.method() == RequestMethod.PUT
        || route.method() == RequestMethod.PATCH) {
      builder.contentType(MediaType.APPLICATION_JSON).content("{}");
    }
    if (bearerToken != null) {
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return builder;
  }

  private static String firstLineOf(String body) {
    if (body == null || body.isBlank()) {
      return "(no body)";
    }
    return body.length() > 200 ? body.substring(0, 200) + "…" : body;
  }

  // --- fixtures --------------------------------------------------------------------------------

  /**
   * The organization the sweep aims at. Fixed per test method via {@link
   * #seedOrganizationWithAnOwner()}; the unauthenticated sweep never reaches a database lookup at
   * all, so any id does there.
   */
  private UUID organizationIdForSweep = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

  private Organization seedOrganizationWithAnOwner() {
    User owner = users.saveAndFlush(user("sweep-owner"));
    Organization org =
        organizations.saveAndFlush(new Organization("Sweep Org", slug(), owner.getId()));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    organizationIdForSweep = org.getId();
    return org;
  }

  private User user(String name) {
    String unique = UUID.randomUUID().toString();
    return new User("auth0|" + name + "-" + unique, name + "-" + unique + "@example.test", name);
  }

  private static String slug() {
    return "sweep-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String tokenFor(User user) {
    return TestJwtSigner.sign(
        testIssuerKeyPair,
        TestIssuerConfig.TEST_ISSUER,
        user.getAuthSubject(),
        AUDIENCE,
        Instant.now().plus(10, ChronoUnit.MINUTES));
  }
}
