#!/usr/bin/env bash
# promtool check + test for deploy/prometheus (#472). Uses the Prometheus image so the
# lint job does not need promtool installed on the runner.
set -euo pipefail
cd "$(dirname "$0")/.."
docker run --rm --entrypoint promtool \
  -v "$PWD/deploy/prometheus:/rules:ro" \
  prom/prometheus:v2.55.1 \
  test rules /rules/alert-rules.test.yml
