package com.opspilot.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * T28 mandatory case "logs and no secrets" (docs/SECURITY_AND_TESTING.md §3), and the AGENTS.md
 * invariant "a trace never logs access tokens, API keys, raw cookies or unnecessary sensitive
 * personal data".
 *
 * <p>The Python worker already proves its side of this — {@code test_provider_internals.py} covers
 * the redaction helper, and {@code test_http_providers.py} covers a provider error body that echoes
 * the key back. The API had nothing equivalent, even though it is the service that actually
 * receives bearer tokens and cookies on every request.
 *
 * <p>What this test is really protecting against is not today's code — nothing in it logs a header
 * deliberately. It is the one-line change that would: a {@code log.debug("request {}", request)}
 * added while debugging, or a logging level raised to DEBUG on {@code org.springframework.security}
 * or {@code org.springframework.web} in a hurry. Both are routine, and both would quietly start
 * writing credentials to wherever the logs go. So the logger is deliberately turned up
 * <em>past</em> what the application ships with: if a credential can reach a log line at TRACE,
 * this fails.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("security")
class SecretHygieneIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  /**
   * Distinctive enough that a substring match cannot be a coincidence, and shaped like the things
   * that actually leak.
   */
  private static final String SESSION_COOKIE_VALUE = "opspilot-session-value-5f3b9c1d-do-not-log";

  private static final String API_KEY_IN_A_HEADER = "sk-live-T28HygieneProbe0000000000";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;

  private CapturingAppender captured;
  private ch.qos.logback.classic.Logger rootLogger;
  private Level previousRootLevel;
  private Level previousSecurityLevel;
  private Level previousWebLevel;

  @BeforeEach
  void captureLogsAtTraceLevel() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

    previousRootLevel = rootLogger.getLevel();
    previousSecurityLevel = context.getLogger("org.springframework.security").getLevel();
    previousWebLevel = context.getLogger("org.springframework.web").getLevel();

    captured = new CapturingAppender();
    captured.setContext(context);
    captured.start();
    rootLogger.addAppender(captured);

    // Louder than production and louder than the `local` profile. The point is to give any
    // credential the widest possible chance to appear.
    rootLogger.setLevel(Level.TRACE);
    context.getLogger("org.springframework.security").setLevel(Level.TRACE);
    context.getLogger("org.springframework.web").setLevel(Level.TRACE);
    context.getLogger("com.opspilot").setLevel(Level.TRACE);
  }

  @AfterEach
  void restoreLogging() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    rootLogger.detachAppender(captured);
    captured.stop();
    rootLogger.setLevel(previousRootLevel);
    context.getLogger("org.springframework.security").setLevel(previousSecurityLevel);
    context.getLogger("org.springframework.web").setLevel(previousWebLevel);
    context.getLogger("com.opspilot").setLevel(null);
  }

  @Test
  void anAcceptedRequestsBearerTokenAndCookiesNeverReachTheLogs() throws Exception {
    User user = users.saveAndFlush(user("hygiene-accepted"));
    String token = tokenFor(user.getAuthSubject());

    mockMvc
        .perform(
            get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Api-Key", API_KEY_IN_A_HEADER)
                .cookie(new Cookie("appSession", SESSION_COOKIE_VALUE)))
        .andExpect(status().isOk());

    assertNoSecretWasLogged(token);
  }

  @Test
  void aRejectedRequestsBearerTokenNeverReachesTheLogsEither() throws Exception {
    // The rejection path is the more dangerous one: "why was this token rejected?" is precisely
    // the question that tempts someone to log the token.
    String forgedToken =
        TestJwtSigner.sign(
            generateAnUnrelatedKeyPair(),
            TestIssuerConfig.TEST_ISSUER,
            "auth0|forged",
            AUDIENCE,
            Instant.now().plus(5, ChronoUnit.MINUTES));

    mockMvc
        .perform(
            get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedToken)
                .cookie(new Cookie("appSession", SESSION_COOKIE_VALUE)))
        .andExpect(status().isUnauthorized());

    assertNoSecretWasLogged(forgedToken);
  }

  @Test
  void aRefusedInternalServiceTokenNeverReachesTheLogs() throws Exception {
    // The internal action API's token is minted from a shared secret. A rejected one in a log file
    // is a free offline guessing target against that secret.
    // Assembled rather than written out: a literal JWT in a source file is exactly what the
    // gitleaks CI job exists to catch, and it caught this one. Adding a scanner allowlist to
    // accommodate a test fixture would blunt the scanner for the sake of three lines, so the
    // fixture is built at runtime instead and the `jwt` rule keeps its teeth.
    String bogusServiceToken =
        base64Url("{\"alg\":\"HS256\"}")
            + "."
            + base64Url("{\"aud\":\"opspilot-internal\"}")
            + "."
            + base64Url("T28HygieneProbeSignature");

    mockMvc
        .perform(
            post("/internal/v1/agent-actions/execute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bogusServiceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());

    assertNoSecretWasLogged(bogusServiceToken);
  }

  /**
   * Asserts the captured log output contains neither the credential under test nor the other
   * probes, and that something was in fact captured — an empty appender would pass every assertion
   * while proving nothing.
   */
  private void assertNoSecretWasLogged(String credential) {
    List<String> lines = captured.snapshot();
    String all = String.join("\n", lines);

    assertThat(lines)
        .as("nothing was logged at TRACE for a whole request — the appender is not attached")
        .isNotEmpty();

    assertThat(all).as("the bearer token itself").doesNotContain(credential);
    // A JWT's signature is the part that must stay secret even if the claims are harmless, so it
    // is checked on its own: a log line that prints only "the last segment" still leaks.
    assertThat(all)
        .as("the token's signature segment")
        .doesNotContain(credential.substring(credential.lastIndexOf('.') + 1));
    assertThat(all).as("the session cookie's value").doesNotContain(SESSION_COOKIE_VALUE);
    assertThat(all).as("an API key supplied in a header").doesNotContain(API_KEY_IN_A_HEADER);
  }

  /**
   * Logback's own {@code ListAppender} keeps a plain {@link java.util.ArrayList}, and the threads
   * that log during a request are not only this one — Hikari, the Redis client and Spring's own
   * background threads all write to the root logger. Streaming that list while another thread
   * appends to it throws {@link java.util.ConcurrentModificationException}, which is exactly what
   * the full suite did on a run the single-class run had passed.
   *
   * <p>So: a concurrent queue, and the event is flattened to a string at append time rather than
   * held — logback reuses and mutates event objects, so keeping references and reading them later
   * is a second race on top of the first.
   */
  private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
    private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();

    @Override
    protected void append(ILoggingEvent event) {
      String throwable =
          event.getThrowableProxy() == null
              ? ""
              : ThrowableProxyUtil.asString(event.getThrowableProxy());
      lines.add(event.getFormattedMessage() + " " + throwable);
    }

    List<String> snapshot() {
      return List.copyOf(lines);
    }
  }

  private static String base64Url(String raw) {
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static KeyPair generateAnUnrelatedKeyPair() throws Exception {
    java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private String tokenFor(String subject) {
    return TestJwtSigner.sign(
        testIssuerKeyPair,
        TestIssuerConfig.TEST_ISSUER,
        subject,
        AUDIENCE,
        Instant.now().plus(10, ChronoUnit.MINUTES));
  }

  private static User user(String label) {
    String unique = UUID.randomUUID().toString();
    return new User("auth0|" + label + "-" + unique, label + "-" + unique + "@example.test", label);
  }
}
