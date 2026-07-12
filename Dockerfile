# Production image for the Process Inspector BFF (SPEC §10 multi-stage build).
# Build:  docker build -t process-inspector .
# Run:    docker run -p 8080:8080 -e ENGINE_A_PASSWORD=... process-inspector
# Dev outside docker stays :8085 (application.yml); the container standard is :8080
# via SERVER_PORT. Secrets arrive as env vars named by the registry's password-refs.

# ---- Stage 1: backend build ------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependency layer first: pom-only changes are rare, so the go-offline layer caches.
COPY backend/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B package -DskipTests

# ---- (future) Stage: frontend build ----------------------------------------
# Placeholder for the Vite build once the SPA ships in this image:
#   FROM node:22-alpine AS frontend
#   COPY frontend/ . && npm ci && npm run build
# ...then COPY --from=frontend /dist into the runtime image's static resources.

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
# OCI provenance for the registry listing (ghcr.io + Docker Hub). The publish
# workflows also inject dynamic labels (revision/version) via docker/metadata-action.
LABEL org.opencontainers.image.title="Process Inspector BFF" \
      org.opencontainers.image.description="Spring Boot BFF for the multi-engine Flowable Process Inspector" \
      org.opencontainers.image.source="https://github.com/x3kcl/process-inspector" \
      org.opencontainers.image.licenses="Apache-2.0"
# Pick up patched Alpine packages ahead of the base image's own next release cut
# (nightly Trivy scan gate, issue #93) — libexpat/p11-kit etc. get fixes upstream
# well before eclipse-temurin re-cuts the jre-alpine tag.
RUN apk upgrade --no-cache
RUN addgroup -S app && adduser -S -G app app
USER app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar
ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
