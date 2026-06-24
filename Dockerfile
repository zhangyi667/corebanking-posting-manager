# syntax=docker/dockerfile:1.7

# ---- build stage: produce the bootJar with cached Gradle deps ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy wrapper + settings first so docker layer cache stays warm across code-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

# ---- runtime stage: slim JRE, non-root user ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home /app app \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD curl -fsS http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
