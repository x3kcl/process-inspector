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
# NOT -DskipTests: backend/pom.xml deliberately binds surefire's skipTests to the custom
# skip.surefire.tests property (so nightly's IT jobs can skip only failsafe via
# -Dskip.surefire.tests=true) — the standard flag is a no-op here and silently re-ran the
# whole 824-test unit suite (already covered by ci.yml's dedicated `unit` job) on every
# image build.
RUN mvn -B package -Dskip.surefire.tests=true

# ---- (future) Stage: frontend build ----------------------------------------
# Placeholder for the Vite build once the SPA ships in this image:
#   FROM node:22-alpine AS frontend
#   COPY frontend/ . && npm ci && npm run build
# ...then COPY --from=frontend /dist into the runtime image's static resources.

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
# OCI provenance for the registry listing (ghcr.io + Docker Hub). The publish
# workflows also inject dynamic labels (revision/version) via docker/metadata-action.
LABEL org.opencontainers.image.title="Process Inspector BFF" \
      org.opencontainers.image.description="Spring Boot BFF for the multi-engine Flowable Process Inspector" \
      org.opencontainers.image.source="https://github.com/x3kcl/process-inspector" \
      org.opencontainers.image.licenses="Apache-2.0"
# Pick up patched Alpine packages ahead of the base image's own next release cut
# (nightly Trivy scan gate, issue #93) — libexpat/p11-kit etc. get fixes upstream
# well before eclipse-temurin re-cuts the jre-alpine tag.
#
# ⚠️ This layer is a LIE under a warm layer cache: its cache key is the literal command
# string, which never changes, so buildkit happily replays a months-old package set while
# the whole point of the step is to resolve packages fresh. That is exactly what reddened
# nightly #66/#67 (CVE-2026-14456, openssl 3.5.7-r0 → fixed in 3.5.8-r0, already in the
# Alpine repo): every layer logged `CACHED`, so the "new" image was a byte-identical
# replay of the one built days earlier. Hence the stage NAME above — every workflow that
# builds a shipping or scanned BFF image passes `no-cache-filters: runtime` to
# docker/build-push-action so this stage always re-resolves against the live Alpine index.
# The expensive maven `build` stage keeps its cache. Do not drop either half.
RUN apk upgrade --no-cache
# Strip packages the base image installs for ITS OWN build/verification tooling that
# this headless JSON REST service never touches at runtime (no AWT/font/PDF/image
# dependency anywhere in backend/pom.xml) — confirmed via `apk info --rdepends` that
# none of these are JRE dependencies, only top-level /etc/apk/world entries. Removing
# them is the only available remediation for CVE-2026-11822/11824/23865/41989
# (libgcrypt, pulled in solely by gnupg), CVE-2016-2781 (coreutils), and
# CVE-2025-30258 (freetype, pulled in solely by fontconfig/ttf-dejavu) — issue #191:
# Alpine has not cut a patched revision of any of the three yet, so `apk upgrade`
# above cannot fix this. Verified the app boots identically without them.
RUN apk del --purge gnupg coreutils fontconfig ttf-dejavu
RUN addgroup -S app && adduser -S -G app app
USER app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar
ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
