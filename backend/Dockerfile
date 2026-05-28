# -------------------------------------------------------
# Multi-stage Dockerfile for Gradle multi-module project
# Usage:
#   docker build --build-arg MODULE=core-api --build-arg PORT=8083 .
#   docker build --build-arg MODULE=collector --build-arg PORT=8081 .
#   docker build --build-arg MODULE=analyzer  --build-arg PORT=8082 .
# -------------------------------------------------------

ARG MODULE=core-api
ARG PORT=8083

# ── Stage 1: Build ──────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /workspace

# Gradle wrapper + root config (cache layer)
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# Module build scripts (cache layer — only invalidated on dependency changes)
COPY common/build.gradle common/build.gradle
COPY core-api/build.gradle core-api/build.gradle
COPY collector/build.gradle collector/build.gradle
COPY analyzer/build.gradle analyzer/build.gradle

RUN chmod +x gradlew

# Warm up Gradle dependency cache
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Source
COPY common/src common/src
COPY core-api/src core-api/src
COPY collector/src collector/src
COPY analyzer/src analyzer/src

ARG MODULE
RUN ./gradlew :${MODULE}:bootJar -x test --no-daemon && \
    find /workspace/${MODULE}/build/libs -name "*.jar" ! -name "*-plain.jar" -exec cp {} /workspace/app.jar \;

# ── Stage 2: Runtime ────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

ARG PORT

COPY --from=builder /workspace/app.jar app.jar

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE ${PORT}

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
