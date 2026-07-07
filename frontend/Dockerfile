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
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
