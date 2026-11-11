package com.opspilot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A Redis container for the outbox publisher tests (T24).
 *
 * <p>Separate from {@link TestcontainersConfiguration} on purpose: almost every integration test in
 * this repo needs Postgres and none of them needs Redis, so folding Redis into the shared
 * configuration would start a second container for twenty test classes that never touch it. Only
 * the publisher tests import this one.
 *
 * <p>A plain {@code GenericContainer} rather than a Testcontainers Redis module — there is no
 * official one, and Spring Boot's {@code @ServiceConnection} recognises the {@code redis} image by
 * name and wires {@code spring.data.redis.*} from it, so no third-party dependency is justified.
 *
 * <p>{@code redis:7-alpine} matches infra/docker-compose.yml exactly, so the tests run against the
 * same server version as local development.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

  /**
   * Skips the container when an external Redis is supplied instead, mirroring {@link
   * TestcontainersConfiguration}'s switch. With {@code -Dopspilot.test.external-redis=true} and the
   * usual {@code REDIS_HOST}/{@code REDIS_PORT} variables, the whole Java suite — publisher tests
   * included — runs on a machine with no Docker. Defaults to the container, so CI is untouched.
   */
  @Bean
  @ServiceConnection(name = "redis")
  @ConditionalOnProperty(
      name = "opspilot.test.external-redis",
      havingValue = "false",
      matchIfMissing = true)
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        // Without an explicit wait the port is open before the server answers, which would make the
        // first publish in a run fail for a reason that has nothing to do with the code under test.
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
  }
}
