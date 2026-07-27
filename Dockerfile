# Multi-stage Dockerfile for CastCLI
# Stage 1: Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build configuration
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Download dependencies
RUN ./gradlew dependencies --no-daemon

# Copy source files and build distribution package
COPY src ./src
COPY config ./config
RUN ./gradlew installDist --no-daemon

# Stage 2: Ephemeral runtime stage
FROM eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

# Create non-root system user and group
RUN addgroup -S castcli && adduser -S castcli -G castcli

# Copy installed distribution from builder stage
COPY --from=builder /app/build/install/cast-cli /app
COPY config /app/config

# config/harness.local.json is gitignored (it's each developer's local override, normally written
# by `cast-cli init`) and excluded via .dockerignore, so the image never bakes one in. Without it,
# cast-cli's default --config path resolves to nothing and even `doctor` can't run. Seed it from the
# example config so the image is usable standalone; a bind-mounted config/ (e.g. docker-compose)
# still overrides this with the real local config.
RUN test -f /app/config/harness.local.json || cp /app/config/harness.example.json /app/config/harness.local.json

# Set ownership
RUN chown -R castcli:castcli /app

USER castcli

ENV PATH="/app/bin:${PATH}"

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["cast-cli", "doctor", "--json"]

ENTRYPOINT ["cast-cli"]
CMD ["mcp-serve"]
