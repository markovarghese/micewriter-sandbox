# syntax=docker/dockerfile:1
# Build context must be the PARENT directory of both repos:
#   docker build -f micewriter-sandbox/Dockerfile -t micewriter-sandbox:latest ..
#
# Skaffold handles this via `context: ..` in skaffold.yaml.

# ---------------------------------------------------------------------------
# Stage 1: Build SDK then sandbox
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Install the SDK into the container-local Maven repo first.
COPY micewriter-sdk-java/pom.xml         ./micewriter-sdk-java/pom.xml
COPY micewriter-sdk-java/src             ./micewriter-sdk-java/src
RUN mvn -f micewriter-sdk-java/pom.xml install -DskipTests -q

# Build the sandbox (SDK is now in the local repo).
COPY micewriter-sandbox/pom.xml          ./micewriter-sandbox/pom.xml
COPY micewriter-sandbox/src              ./micewriter-sandbox/src
RUN mvn -f micewriter-sandbox/pom.xml package -DskipTests -q

# ---------------------------------------------------------------------------
# Stage 2: Runtime — minimal JRE image
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

RUN useradd -r -u 1000 -g daemon micewriter
USER micewriter

WORKDIR /app
COPY --from=builder /build/micewriter-sandbox/target/micewriter-sandbox-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
