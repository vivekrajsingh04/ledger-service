# Multi-stage: the runtime image carries a JRE and one jar, not Maven and a
# ~500MB local repository.
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy

FROM ${BUILD_IMAGE} AS build
WORKDIR /build

# Dependencies first, in their own layer: pom.xml changes far less often than
# source, so an ordinary code change reuses the cached dependency download.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM ${RUNTIME_IMAGE}
WORKDIR /app

# Non-root. The service holds a database credential; there is no reason for it
# to also hold uid 0.
RUN useradd --create-home --uid 10001 ledger
USER ledger

COPY --from=build --chown=ledger /build/target/ledger-service-*.jar app.jar

EXPOSE 8080

# Container-aware heap sizing: without MaxRAMPercentage the JVM reads the host's
# memory, not the cgroup limit, and gets OOM-killed under a memory cap.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=30s \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
