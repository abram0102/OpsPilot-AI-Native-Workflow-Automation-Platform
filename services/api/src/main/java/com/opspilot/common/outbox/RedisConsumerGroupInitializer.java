package com.opspilot.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the consumer group the worker reads from (docs/API_CONTRACT.md: stream {@code
 * opspilot:events}, group {@code agent-workers}).
 *
 * <p>Created by the publishing side rather than the consumer, deliberately: a worker that creates
 * its own group on first start would miss everything published before it ever ran, because a group
 * created at {@code $} only sees messages added after it. Creating it here, from offset 0, means
 * nothing is published into a stream nobody is grouped on.
 *
 * <p>T25 builds the consumer itself; this is only the group it will join.
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox.publisher", name = "enabled", havingValue = "true")
class RedisConsumerGroupInitializer {

  private static final Logger log = LoggerFactory.getLogger(RedisConsumerGroupInitializer.class);

  RedisConsumerGroupInitializer(
      StringRedisTemplate redis,
      @Value("${app.outbox.stream-key}") String streamKey,
      @Value("${app.outbox.consumer-group}") String consumerGroup) {
    try {
      // Creating a group on a stream that does not exist yet creates the stream too, so startup
      // order between publisher and consumer does not matter.
      redis.opsForStream().createGroup(streamKey, ReadOffset.from("0"), consumerGroup);
      log.info("Created Redis consumer group {} on {}.", consumerGroup, streamKey);
    } catch (RuntimeException e) {
      // BUSYGROUP — the group already exists — is the normal case on every restart after the
      // first, and is success rather than failure. Any other error (Redis down at boot) is
      // tolerated too: the outbox stays durable regardless, and the next start retries.
      log.info(
          "Redis consumer group {} on {} was not created ({}). Expected when it already exists,"
              + " and harmless when Redis is unavailable — the outbox accrues either way.",
          consumerGroup,
          streamKey,
          e.getClass().getSimpleName());
    }
  }
}
