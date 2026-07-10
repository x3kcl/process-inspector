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
FROM myoung34/github-runner:ubuntu-noble

ARG MAVEN_VERSION=3.9.9
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 jq \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn
