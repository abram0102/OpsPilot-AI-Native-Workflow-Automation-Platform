# syntax=docker/dockerfile:1
#
# Spring Boot API (docs/TASKS.md T32).
#
# Multi-stage for a reason that matters beyond image size: the runtime stage has no JDK, no Maven,
# no source and no build cache. A compiler in a production image is a tool for whoever gets into
# it, and `mvnw` would happily fetch and run plugins from the internet.
#
# Built for linux/arm64 — T31's task definitions set `cpu_architecture = "ARM64"` (Graviton is
# cheaper per vCPU-hour for a JVM). The build workflow passes --platform; building this on an
# x86 laptop without it produces an image ECS will refuse to start, which is a confusing failure,
# so the FROM lines do not pin an architecture and let the builder decide.

# --- build ---------------------------------------------------------------------------------------

FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies first, as their own layer. The POM changes far less often than the source, so a
# code-only change reuses the downloaded repository instead of refetching it.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

COPY src ./src

# -DskipTests deliberately: the tests need Testcontainers, i.e. a Docker daemon inside the build.
# They are not skipped in any meaningful sense — CI runs the whole suite in its own job before
# this image is ever built, and an image is only pushed from a commit whose tests passed.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package

# Unpack the fat jar into layers. Spring Boot's layertools splits dependencies (rarely change)
# from application classes (change every commit), so a redeploy ships kilobytes rather than the
# whole jar.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# --- runtime -------------------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-noble AS runtime

# curl for the container health check in T31's task definition. --no-install-recommends keeps it
# to the one package.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# A fixed non-root uid. Numeric so it is still non-root if the image is run somewhere that does
# not resolve the name, and the same across all three images so volume permissions behave
# identically.
# uid/gid 10001, not 1000. The base images disagree about who owns 1000: Ubuntu Noble (the API's
# JRE base) ships a default `ubuntu` user there and node:26 ships `node` there, so `groupadd
# --gid 1000` exits 4, "GID already in use". Debian slim happens to leave it free, which is
# exactly the kind of inconsistency that makes one image fail and another pass for no visible
# reason — CI caught it on the API and the web image would have failed the same way one step
# later. A high, fixed id is free on all three and collides with no host user either.
RUN groupadd --gid 10001 opspilot \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin opspilot

WORKDIR /app

COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./

USER 10001:10001

EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM should size its heap from the container's limit, which the
# task definition sets and this file cannot know. 75% leaves room for metaspace, thread stacks
# and direct buffers inside the same limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

# ExitOnOutOfMemoryError is deliberate: a JVM that has exhausted its heap is not going to recover,
# and a task that dies is replaced by ECS, whereas one that thrashes stays "healthy" and serves
# nothing.

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

# JarLauncher, not `java -jar app.jar`. A layered extraction has no jar to run — it lays out
# BOOT-INF/ and the loader classes as directories, and the manifest's Main-Class is the launcher.
# `java -jar app.jar` was the first thing written here and it builds perfectly; it fails at
# runtime, which is the worst place to find out. Verified against the real extraction layout.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
