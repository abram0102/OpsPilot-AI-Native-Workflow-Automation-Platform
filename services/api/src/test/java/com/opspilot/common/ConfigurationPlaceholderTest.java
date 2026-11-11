package com.opspilot.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Every {@code @Value("${...}")} placeholder without a default must exist in the shipped YAML.
 *
 * <p>Written after a real CI failure: a T21 key was added to {@code application.yml} one
 * indentation level too shallow, landing at {@code app.internal-service.audience} instead of {@code
 * app.security.internal-service.audience}. Startup then failed with {@code
 * PlaceholderResolutionException} — but *only in CI*, because every test that loads the Spring
 * context needs Docker (Gate H0), so nothing locally could have caught it.
 *
 * <p>This test needs no Spring context and no database, which is the point: it closes a class of
 * bug that was otherwise structurally invisible until a push. It is deliberately narrow — it checks
 * that a key exists, not that its value is sensible.
 */
class ConfigurationPlaceholderTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");
  private static final Path RESOURCES = Path.of("src/main/resources");

  /** {@code @Value("${some.key}")} — the capture excludes anything carrying a {@code :default}. */
  private static final Pattern VALUE_WITHOUT_DEFAULT =
      Pattern.compile("@Value\\(\\s*\"\\$\\{([^:}]+)}\"");

  /**
   * {@code @Scheduled(fixedDelayString = "${some.key}")} and friends, added in T24.
   *
   * <p>Same failure mode as {@code @Value} and the same invisibility locally: a misplaced key makes
   * the scheduled bean fail to start, and every context-loading test needs Docker.
   */
  private static final Pattern SCHEDULED_WITHOUT_DEFAULT =
      Pattern.compile(
          "(?:fixedDelayString|fixedRateString|initialDelayString|cron)\\s*=\\s*\"\\$\\{([^:}]+)}\"");

  @Test
  void everyPlaceholderWithoutADefaultIsDefinedInTheShippedConfiguration() throws IOException {
    Map<String, String> flattened = new LinkedHashMap<>();
    for (Path yaml : configurationFiles()) {
      flatten("", new Yaml().load(Files.readString(yaml)), flattened);
    }

    Set<String> missing = new LinkedHashSet<>();
    for (Path source : javaSources()) {
      String body = Files.readString(source);
      for (Pattern pattern : List.of(VALUE_WITHOUT_DEFAULT, SCHEDULED_WITHOUT_DEFAULT)) {
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
          String key = matcher.group(1).trim();
          if (!flattened.containsKey(key)) {
            missing.add(key + "  (referenced by " + source.getFileName() + ")");
          }
        }
      }
    }

    assertThat(missing)
        .as(
            "these @Value placeholders have no default and no matching key in"
                + " src/main/resources/application*.yml, so the application context will fail to"
                + " start — check the YAML indentation of the key you just added")
        .isEmpty();
  }

  private static List<Path> configurationFiles() throws IOException {
    try (Stream<Path> files = Files.list(RESOURCES)) {
      return files
          .filter(path -> path.getFileName().toString().matches("application.*\\.ya?ml"))
          .sorted()
          .toList();
    }
  }

  private static List<Path> javaSources() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }

  @SuppressWarnings("unchecked")
  private static void flatten(String prefix, Object node, Map<String, String> into) {
    if (node instanceof Map<?, ?> map) {
      map.forEach((key, value) ->
          flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, value, into));
      return;
    }
    if (node instanceof List<?> list) {
      // Lists are not addressable as placeholder keys; record the prefix itself as present.
      into.put(prefix, String.valueOf(new ArrayList<>(list)));
      return;
    }
    into.put(prefix, String.valueOf(node));
  }
}
