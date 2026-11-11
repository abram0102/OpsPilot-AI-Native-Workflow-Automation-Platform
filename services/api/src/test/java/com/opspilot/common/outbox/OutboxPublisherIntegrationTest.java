package com.opspilot.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.RedisTestcontainersConfiguration;
import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T24 acceptance, end to end against a real Postgres and a real Redis.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional}: the publisher's own transaction, and the
 * fact that two publishers can claim disjoint rows concurrently, are the behaviours under test. A
 * test-managed rollback would hide both — rows would never be visible to a second connection, and
 * {@code FOR UPDATE SKIP LOCKED} would have nothing to skip.
 */
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
@SpringBootTest
class OutboxPublisherIntegrationTest {

  @Autowired private OutboxPublisher publisher;
  @Autowired private OutboxEventRepository outboxEvents;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @Value("${app.outbox.stream-key}")
  private String streamKey;

  @Value("${app.outbox.max-attempts}")
  private int maxAttempts;

  private UUID orgId;

  @BeforeEach
  void reset() {
    // The claim query is global by design — a publisher drains every tenant's backlog — so leftover
    // rows from an earlier test would be claimed by this one.
    outboxEvents.deleteAll();
    redis.delete(streamKey);

    User owner = users.saveAndFlush(user());
    orgId =
        organizations.saveAndFlush(new Organization("Outbox Org", slug(), owner.getId())).getId();
  }

  @Test
  void publishesAPendingRowAndMarksItPublished() {
    OutboxEvent row = save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run");

    assertThat(publisher.publishBatch()).isEqualTo(1);

    OutboxEvent published = outboxEvents.findById(row.getId()).orElseThrow();
    assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    assertThat(published.getPublishedAt()).isNotNull();
    assertThat(published.getNextAttemptAt()).isNull();

    List<JsonNode> envelopes = streamEnvelopes();
    assertThat(envelopes).hasSize(1);

    JsonNode envelope = envelopes.getFirst();
    assertThat(envelope.get("eventId").asString()).isEqualTo(row.getId().toString());
    assertThat(envelope.get("type").asString()).isEqualTo(OutboxEventType.AGENT_RUN_REQUESTED);
    assertThat(envelope.get("aggregateId").asString()).isEqualTo(row.getAggregateId().toString());
    assertThat(envelope.get("organizationId").asString()).isEqualTo(orgId.toString());
    assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(OutboxEventEnvelope.SCHEMA_VERSION);
    assertThat(envelope.has("createdAt")).isTrue();

    // No tenant content on the wire (docs/API_CONTRACT.md: identifiers only).
    List<String> fields = new ArrayList<>();
    envelope.propertyNames().forEach(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "eventId", "type", "aggregateId", "organizationId", "schemaVersion", "createdAt");
  }

  @Test
  void anEmptyBacklogIsANoOp() {
    assertThat(publisher.publishBatch()).isZero();
    assertThat(streamEnvelopes()).isEmpty();
  }

  @Test
  void aPublishedRowIsNotPublishedTwiceByALaterTick() {
    save(OutboxEventType.KNOWLEDGE_INGEST_REQUESTED, "knowledge_document");

    assertThat(publisher.publishBatch()).isEqualTo(1);
    assertThat(publisher.publishBatch()).isZero();

    assertThat(streamEnvelopes()).hasSize(1);
  }

  @Test
  void drainsTheBacklogOldestFirst() {
    OutboxEvent first = save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run");
    OutboxEvent second = save(OutboxEventType.AGENT_RUN_RESUME, "agent_run");
    OutboxEvent third = save(OutboxEventType.KNOWLEDGE_INGEST_REQUESTED, "knowledge_document");
    // created_at defaults to now() at insert; make the intended order unambiguous rather than
    // relying on three inserts landing in distinguishable microseconds.
    backdate(first, 30);
    backdate(second, 20);
    backdate(third, 10);

    assertThat(publisher.publishBatch()).isEqualTo(3);

    assertThat(streamEnvelopes())
        .extracting(node -> node.get("eventId").asString())
        .containsExactly(
            first.getId().toString(), second.getId().toString(), third.getId().toString());
  }

  @Test
  void aRowWhoseTypeNothingRoutesIsDeadLetteredAndNeverReachesTheStream() {
    // Exactly what the old knowledge.document.queued name would now be: a type no consumer routes.
    OutboxEvent row = save("knowledge.document.queued", "knowledge_document");

    assertThat(publisher.publishBatch()).isZero();

    assertThat(outboxEvents.findById(row.getId()).orElseThrow().getStatus())
        .isEqualTo(OutboxEventStatus.DEAD);
    assertThat(streamEnvelopes()).isEmpty();
    // DEAD, not deleted — the row stays visible to an operator (docs/SECURITY_AND_TESTING.md).
    assertThat(outboxEvents.countByStatus(OutboxEventStatus.DEAD)).isEqualTo(1);
  }

  @Test
  void anUnroutableRowDoesNotBlockTheRowsBehindIt() {
    OutboxEvent poison = save("ticket.invented.event", "ticket");
    OutboxEvent good = save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run");
    backdate(poison, 30);
    backdate(good, 20);

    assertThat(publisher.publishBatch()).isEqualTo(1);

    assertThat(outboxEvents.findById(poison.getId()).orElseThrow().getStatus())
        .isEqualTo(OutboxEventStatus.DEAD);
    assertThat(outboxEvents.findById(good.getId()).orElseThrow().getStatus())
        .isEqualTo(OutboxEventStatus.PUBLISHED);
    assertThat(streamEnvelopes())
        .extracting(node -> node.get("eventId").asString())
        .containsExactly(good.getId().toString());
  }

  @Test
  void whenRedisIsUnavailableTheRowStaysPendingAndTheBacklogDrainsOnRecovery() {
    OutboxEvent row = save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run");

    assertThat(publishWithUnreachableRedis()).isZero();

    OutboxEvent afterOutage = outboxEvents.findById(row.getId()).orElseThrow();
    assertThat(afterOutage.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
    assertThat(afterOutage.getAttempts()).isEqualTo(1);
    assertThat(afterOutage.getPublishedAt()).isNull();
    // Backed off rather than retried immediately, so a long outage is not a tight loop.
    assertThat(afterOutage.getNextAttemptAt()).isAfter(Instant.now());
    // Nothing is claimable until the backoff elapses.
    assertThat(publisher.publishBatch()).isZero();

    // Stand in for the backoff elapsing. Only the clock is faked; the publish that follows is the
    // real one, against the real Redis.
    letBackoffElapse();

    assertThat(publisher.publishBatch()).isEqualTo(1);
    assertThat(outboxEvents.findById(row.getId()).orElseThrow().getStatus())
        .isEqualTo(OutboxEventStatus.PUBLISHED);
    assertThat(streamEnvelopes())
        .extracting(node -> node.get("eventId").asString())
        .containsExactly(row.getId().toString());
  }

  @Test
  void backoffGrowsWithEachFailedAttempt() {
    OutboxEvent row = save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run");

    publishWithUnreachableRedis();
    Instant firstDelay = outboxEvents.findById(row.getId()).orElseThrow().getNextAttemptAt();
    letBackoffElapse();

    publishWithUnreachableRedis();
    OutboxEvent twiceFailed = outboxEvents.findById(row.getId()).orElseThrow();

    assertThat(twiceFailed.getAttempts()).isEqualTo(2);
    // The second wait is longer than the first measured from the same baseline: attempt 1 waits
    // 2 × base, attempt 2 waits 4 × base.
    assertThat(twiceFailed.getNextAttemptAt()).isAfter(firstDelay);
  }

  @Test
  void repeatedFailuresEndInDeadRatherThanAnEndlessRetryLoop() {
    OutboxEvent row = save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run");
    // Fast-forward to one attempt short of the cap instead of running the real backoff maxAttempts
    // times, which would take minutes of wall clock for no extra coverage.
    jdbc.update("UPDATE outbox_events SET attempts = ? WHERE id = ?", maxAttempts - 1, row.getId());

    assertThat(publishWithUnreachableRedis()).isZero();

    OutboxEvent dead = outboxEvents.findById(row.getId()).orElseThrow();
    assertThat(dead.getAttempts()).isEqualTo(maxAttempts);
    assertThat(dead.getStatus()).isEqualTo(OutboxEventStatus.DEAD);
    assertThat(dead.getNextAttemptAt()).isNull();

    // Terminal: a DEAD row is never claimed again, even once Redis is back.
    assertThat(publisher.publishBatch()).isZero();
    assertThat(streamEnvelopes()).isEmpty();
  }

  @Test
  void twoPublishersClaimDisjointRows() throws Exception {
    List<OutboxEvent> rows =
        List.of(
            save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run"),
            save(OutboxEventType.AGENT_RUN_RESUME, "agent_run"),
            save(OutboxEventType.KNOWLEDGE_INGEST_REQUESTED, "knowledge_document"),
            save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run"));

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    CountDownLatch firstHasClaimed = new CountDownLatch(1);
    CountDownLatch secondHasClaimed = new CountDownLatch(1);
    List<UUID> claimedByFirst = new ArrayList<>();
    List<UUID> claimedBySecond = new ArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      pool.submit(() ->
          tx.execute(status -> {
            outboxEvents.claimPublishable(Instant.now(), 2).stream()
                .map(OutboxEvent::getId)
                .forEach(claimedByFirst::add);
            firstHasClaimed.countDown();
            // Hold the lock while the second publisher tries, which is the only way to
            // observe SKIP LOCKED doing anything.
            awaitQuietly(secondHasClaimed);
            return null;
          }));
      assertThat(firstHasClaimed.await(30, TimeUnit.SECONDS)).isTrue();

      pool.submit(() ->
              tx.execute(status -> {
                outboxEvents.claimPublishable(Instant.now(), 10).stream()
                    .map(OutboxEvent::getId)
                    .forEach(claimedBySecond::add);
                secondHasClaimed.countDown();
                return null;
              }))
          .get(30, TimeUnit.SECONDS);
    } finally {
      secondHasClaimed.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(claimedByFirst).hasSize(2);
    // The second did not block waiting for the first, and did not re-claim what the first holds.
    assertThat(claimedBySecond).hasSize(2).doesNotContainAnyElementsOf(claimedByFirst);
    assertThat(concat(claimedByFirst, claimedBySecond))
        .containsExactlyInAnyOrderElementsOf(rows.stream().map(OutboxEvent::getId).toList());
  }

  @Test
  void aCrashBeforeTheRowIsMarkedRepublishesTheSameEventId() {
    OutboxEvent row = save(OutboxEventType.AGENT_RUN_REQUESTED, "agent_run");

    assertThat(publisher.publishBatch()).isEqualTo(1);

    // Simulate the documented at-least-once window: the XADD succeeded, the process died before
    // the row was marked, so the row is PENDING again with the stream entry already written.
    jdbc.update(
        "UPDATE outbox_events SET status = 'PENDING', published_at = NULL WHERE id = ?",
        row.getId());

    assertThat(publisher.publishBatch()).isEqualTo(1);

    List<JsonNode> envelopes = streamEnvelopes();
    assertThat(envelopes).hasSize(2);
    // Two deliveries, one identity: this is precisely what lets T25's consumer de-duplicate rather
    // than execute the effect twice.
    assertThat(envelopes)
        .extracting(node -> node.get("eventId").asString())
        .containsExactly(row.getId().toString(), row.getId().toString());
  }

  @Test
  void aConsumerCanParseWhatWasPublishedBackIntoAnAcceptedEnvelope() {
    OutboxEvent row = save(OutboxEventType.KNOWLEDGE_INGEST_REQUESTED, "knowledge_document");
    publisher.publishBatch();

    OutboxEventEnvelope roundTripped =
        objectMapper.readValue(rawEnvelopes().getFirst(), OutboxEventEnvelope.class);

    assertThat(roundTripped.eventId()).isEqualTo(row.getId());
    assertThat(roundTripped.organizationId()).isEqualTo(orgId);
    assertThat(OutboxEnvelopeVerdict.classify(roundTripped))
        .isEqualTo(OutboxEnvelopeVerdict.ACCEPTED);
  }

  // --- helpers -------------------------------------------------------------------------------

  /**
   * Runs one batch through a publisher wired to a closed port.
   *
   * <p>A second publisher instance rather than stopping the container: stopping it would leave
   * every later test in the class racing its restart, and "Redis unreachable" is the same failure
   * either way — the Lettuce client cannot connect.
   */
  private int publishWithUnreachableRedis() {
    LettuceConnectionFactory broken = new LettuceConnectionFactory("127.0.0.1", 1);
    broken.afterPropertiesSet();
    broken.start();
    try {
      StringRedisTemplate brokenTemplate = new StringRedisTemplate(broken);
      brokenTemplate.afterPropertiesSet();
      OutboxPublisher offlinePublisher =
          new OutboxPublisher(
              outboxEvents,
              brokenTemplate,
              objectMapper,
              streamKey,
              100,
              maxAttempts,
              java.time.Duration.ofSeconds(1));
      // The real bean is proxied for @Transactional; a hand-built one is not, so supply the
      // transaction the claim query and the row updates expect.
      Integer published =
          new TransactionTemplate(transactionManager)
              .execute(status -> offlinePublisher.publishBatch());
      return published == null ? 0 : published;
    } finally {
      broken.stop();
      broken.destroy();
    }
  }

  private OutboxEvent save(String type, String aggregateType) {
    return outboxEvents.saveAndFlush(
        new OutboxEvent(type, aggregateType, UUID.randomUUID(), orgId, "{\"documentId\":\"x\"}"));
  }

  /** Moves a row's creation time into the past so ordering assertions are not microsecond races. */
  private void backdate(OutboxEvent event, int secondsAgo) {
    jdbc.update(
        "UPDATE outbox_events SET created_at = now() - make_interval(secs => ?) WHERE id = ?",
        secondsAgo,
        event.getId());
  }

  /** Pretends the backoff window has passed, without sleeping through it. */
  private void letBackoffElapse() {
    jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - make_interval(secs => 1)");
  }

  private List<String> rawEnvelopes() {
    List<MapRecord<String, Object, Object>> records =
        redis.opsForStream().read(StreamOffset.fromStart(streamKey));
    List<String> payloads = new ArrayList<>();
    if (records != null) {
      for (MapRecord<String, Object, Object> record : records) {
        payloads.add(String.valueOf(record.getValue().get(OutboxPublisher.PAYLOAD_FIELD)));
      }
    }
    return payloads;
  }

  private List<JsonNode> streamEnvelopes() {
    return rawEnvelopes().stream().map(objectMapper::readTree).toList();
  }

  private static List<UUID> concat(List<UUID> a, List<UUID> b) {
    List<UUID> all = new ArrayList<>(a);
    all.addAll(b);
    return all;
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static User user() {
    String unique = UUID.randomUUID().toString();
    return new User("auth|outbox-" + unique, "outbox-" + unique + "@example.com", "Outbox Owner");
  }

  private static String slug() {
    return "outbox-org-" + UUID.randomUUID();
  }
}
