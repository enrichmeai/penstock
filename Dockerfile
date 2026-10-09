# Multi-stage build: Gradle 8.14.6 + JDK 21 to compile, then a JDK 21 runtime.
#
# The runtime image carries a JDK and a seeded Gradle home, not just a JRE,
# because the agent is expected to build and test the code it edits. Without a
# compiler it can only write plausible-looking code and hope — see
# docs/real-repo-validation.md, where it invented a Spring annotation that does
# not exist and had no way to find out.

# ----- build stage -----
# Pinned to the BUILDER's platform: everything this stage produces (the jar,
# the Gradle distribution, the dependency seed) is arch-independent bytecode,
# so multi-arch releases build it once natively instead of re-running the
# whole test suite under QEMU emulation per target arch. Only the runtime
# stage below is per-architecture.
#
# Requires BuildKit ($BUILDPLATFORM is BuildKit-defined) — the default builder
# since Docker 23. On older engines: DOCKER_BUILDKIT=1 docker build ...
# Gradle 8.14.6 ships bcprov-jdk18on 1.85, plexus-utils 3.6.1, Jackson 2.18.11 and jsoup 1.23.2,
# the fixes the v0.5.0 scan listed for 8.14.5's distribution (#120). The image carries this
# distribution twice, as /opt/gradle and as the wrapper's in the seed, so the tag and
# gradle-wrapper.properties move together.
FROM --platform=$BUILDPLATFORM gradle:8.14.6-jdk21 AS build
WORKDIR /workspace

# Populate a Gradle home we can ship: the wrapper distribution plus every
# dependency this project resolves, so the agent's first build needs no network.
ENV GRADLE_USER_HOME=/gradle-seed

# Copy build descriptors first to maximise layer caching
COPY settings.gradle build.gradle ./
COPY gradle ./gradle
COPY gradlew bootstrap.sh ./
# gradle-wrapper.jar is deliberately not in the repo (CLAUDE.md, bootstrap.sh, and
# a CI step all say so), so the build stage fetches it exactly as a developer or
# CI would. The runtime image ships a Gradle binary instead of this wrapper, so
# the agent never needs the jar at run time.
RUN chmod +x gradlew bootstrap.sh && ./bootstrap.sh > /dev/null

COPY src ./src
# The suite reads this repository's own memory cards: MemoryRetrievalIT and AcpStdioIT prove
# the real seed pattern (patterns/stdio-json-rpc-agent) loads, not a fixture standing in for
# it, from agent.memory.root's default (the working directory). Build stage only: the runtime
# image below ships the jar, not these folders.
COPY patterns ./patterns
COPY references ./references
COPY requests ./requests
COPY facts ./facts
COPY episodes ./episodes
COPY memory.yaml ./
RUN ./gradlew --no-daemon clean bootJar -x test
# Actually run the suite, rather than just compiling it. testClasses resolves the
# test *compile* classpath only, which leaves junit-platform-launcher and h2
# unresolved — both are needed solely at test runtime, so the agent's first
# `./gradlew test` fails on an offline host despite the sources compiling fine.
# Running the tests here also means a broken suite fails the image build.
RUN ./gradlew --no-daemon test

# ----- runtime stage -----
FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Non-root user
RUN useradd -ms /bin/bash agent
WORKDIR /app

COPY --from=build /workspace/build/libs/penstock-*.jar /app/agent.jar

# The seed is copied into GRADLE_USER_HOME on first start (see entrypoint).
# It cannot be used in place: Gradle writes locks and caches into its home, and
# the container filesystem is read-only.
COPY --from=build /gradle-seed /opt/gradle-seed
# Gradle itself, so the agent can build without the wrapper — and therefore
# without bootstrap.sh, which it could never run: curl and wget are on the shell
# block-list and the offline stack has no network. Keeps gradle-wrapper.jar out
# of the repository, which is a deliberate, documented project decision.
COPY --from=build /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"

COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# Workspace + DB volume mount points
RUN mkdir -p /workspace /data /home/agent/.gradle \
    && chown -R agent:agent /app /workspace /data /home/agent /opt/gradle-seed
USER agent

# Auth is ON by the APP's own default now (AgentProperties.Auth) — no ENV
# needed here; one source of truth. With no AGENT_AUTH_PASSWORD set, a random
# password is generated and logged once at startup (docker logs <container>).
# Opt out explicitly for local experiments with -e AGENT_AUTH_ENABLED=false.
ENV AGENT_WORKSPACE=/workspace \
    AGENT_SQLITE_PATH=/data/agent.db \
    GRADLE_USER_HOME=/home/agent/.gradle \
    JAVA_OPTS=""

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
    CMD curl -fsS http://localhost:8080/api/health > /dev/null || exit 1

# Recommended production run:
#   docker run --read-only --tmpfs /tmp:exec --tmpfs /home/agent/.cache \
#              --cap-drop=ALL --pids-limit=256 --memory=1g \
#              -v agent-data:/data -v agent-gradle:/home/agent/.gradle \
#              -v /path/to/workspace:/workspace \
#              -p 8080:8080 penstock:latest

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
