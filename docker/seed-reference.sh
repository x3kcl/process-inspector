#!/usr/bin/env bash
# FIX-REF-01 reference-dataset generator (TEST-SCENARIOS.md §1.6, issue #93) — thin wrapper
# so the invocation matches the path the docs already reference. See seed-reference.py's
# module docstring for the full generation design.
#
# Usage:
#   docker/seed-reference.sh                       # full scale, every reachable KNOWN_PORTS engine
#   docker/seed-reference.sh <base-url> [<base-url> ...]
#   docker/seed-reference.sh --scale 0.01           # 1% scale, for a dry run
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
exec python3 "$DIR/seed-reference.py" "$@"
