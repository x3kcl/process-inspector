# Demo image for the Process Inspector SPA: build the Vite bundle, then serve it from nginx
# with /api reverse-proxied to the BFF (see nginx.conf). Used by docker/docker-compose.demo.yml.
# Build:  docker build -t process-inspector-frontend frontend/

# ---- Stage 1: build the SPA -------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
# Dependency layer first so lockfile-only rebuilds reuse the npm ci cache.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# `npm run build` = watermark + no-enterprise guards, tsc -b, then vite build (package.json).
# The api client is origin-relative, so the bundle needs no build-time API URL.
RUN npm run build

# ---- Stage 2: serve ---------------------------------------------------------
# Stage NAME matters: every workflow that builds this image passes
# `no-cache-filters: runtime` so the `apk upgrade` below is never served from a warm
# buildkit cache. Its cache key is the literal command string, which never changes, so a
# cached layer replays a months-old package set while pretending to be a fresh upgrade —
# that is exactly what kept a fixed openssl out of the BFF image in #404. Do not drop
# either half of the pair.
FROM nginx:1.27-alpine AS runtime
# Pick up patched Alpine packages ahead of the base image's own next release cut — the
# same remediation the BFF runtime image has carried since #93, and the reason issue #409
# existed: without it this image shipped 35 fixable HIGH/CRITICAL findings (2 CRITICAL),
# including openssl 3.3.3-r0 against a published 3.3.7-r0, because nothing upgraded and
# nothing scanned it. `nginx:1.27-alpine` tracks alpine 3.21, whose package set is well
# behind the tag's own age.
# Safe for the pinned nginx: nginx here comes from the alpine 3.21 repo (see
# /etc/apk/repositories — no upstream nginx repo), and 1.27.5-r1 is already the newest
# revision there, so this upgrades libraries WITHOUT moving nginx off 1.27.5. Verified.
RUN apk upgrade --no-cache
# OCI provenance for the registry listing (ghcr.io + Docker Hub). The publish
# workflows also inject dynamic labels (revision/version) via docker/metadata-action.
LABEL org.opencontainers.image.title="Process Inspector Web" \
      org.opencontainers.image.description="nginx serving the Process Inspector SPA with /api reverse-proxied to the BFF" \
      org.opencontainers.image.source="https://github.com/x3kcl/process-inspector" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
