# CI runner image for x3kcl/process-inspector.
#
# Base: myoung34/github-runner — handles the runner registration lifecycle the official
# ghcr.io/actions/actions-runner image leaves out (PAT -> short-lived registration token
# at start, fresh re-registration after every ephemeral job).
#
# Adds the host tools the CI gates invoke that the base image does not ship:
# - Maven (ci.yml calls `mvn` directly; the repo has no wrapper)
# - python3 (docker/smoke-test.sh + docker/seed.sh helpers)
# - jq (scripts/)
# - Playwright's chromium binary + OS deps, baked in (see below)
FROM myoung34/github-runner:ubuntu-noble

ARG MAVEN_VERSION=3.9.9
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 jq \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn

# Playwright + axe CI gate (#85): the runner container is EPHEMERAL (recreated per job) and
# only /opt/hostedtoolcache survives restarts, so re-downloading the ~150-300MB chromium
# binary AND re-running `apt-get install` for its OS libs (glibc/libnss/libatk/...) on every
# single job would be slow and network-flaky. Baking the browser + its OS deps into the image
# (same tradeoff already accepted for Maven above) means only an image rebuild — never a CI
# job — pays that cost. PLAYWRIGHT_VERSION must move in lockstep with frontend/package.json's
# @playwright/test — a mismatched CLI-installed browser revision fails at test time with
# "browser not found for this version of Playwright".
ARG PLAYWRIGHT_VERSION=1.61.1
ARG NODE_MAJOR=22
RUN curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/* \
 && npx --yes "playwright@${PLAYWRIGHT_VERSION}" install --with-deps chromium
