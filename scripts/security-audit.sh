#!/usr/bin/env bash
#
# security-audit.sh — CI gate for the env-ref secrets doctrine (SPEC §2 / OPERATIONS §7,
# release gate SPEC §13: "security sign-off — whitelist, secrets, guard-bypass").
#
# Fails the build if the codebase contains:
#   1. literal password/secret VALUES in shipping backend config (only ${ENV} refs and
#      `password-ref:` indirections are legal),
#   2. a `password-ref:` whose value is not a bare env-var NAME,
#   3. ENGINE_*_PASSWORD literal assignments in shipping source (backend main / frontend),
#   4. basic-auth credentials embedded in URLs (scheme://user:pass@host) anywhere tracked,
#   5. hardcoded `Authorization: Basic <base64>` header literals in shipping source,
#   6. private-key blocks anywhere tracked.
#
# Scope: TRACKED FILES ONLY (git grep) — an untracked local .env can hold dev secrets;
# committing it is what this gate exists to catch.
#
# Deliberately allowed (the documented dev/test surface — flowable-rest ships with
# rest-admin/test, OPERATIONS §9):
#   - `password-ref: SOME_ENV_NAME` anywhere (that IS the doctrine),
#   - ${ENV:default} placeholders in config,
#   - ENGINE_*_PASSWORD=... in backend/src/test, docker/, CI workflows, docs, README,
#     Dockerfile comments, and .claude/ skills — dev-engine credentials are public
#     fixtures, and test properties must inject them.
#
# Usage: scripts/security-audit.sh   (exit 0 = clean, exit 1 = findings; run from repo root)

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

FAILURES=0

report() { # $1 = check title, $2 = matches (empty = pass)
  local title="$1" matches="$2"
  if [[ -n "$matches" ]]; then
    echo "✗ FAIL: ${title}"
    echo "${matches}" | sed 's/^/    /'
    FAILURES=$((FAILURES + 1))
  else
    echo "✓ ok:   ${title}"
  fi
}

# Pathspecs: this script's own patterns must not trip the gate.
SELF=':!scripts/security-audit.sh'

# ---------------------------------------------------------------------------------------
# 1. Literal secret values in shipping backend config.
#    Legal forms:   password: ${SOME_ENV}         (env ref, optionally with :default)
#                   password-ref: SOME_ENV_NAME   (checked separately in #2)
#    Illegal:       password: hunter2
report "no literal password/secret values in backend/src/main config" \
  "$(git grep -nEi '(password|secret|api[-_]?key)[[:space:]]*[:=][[:space:]]*[^[:space:]$]' -- \
        'backend/src/main/resources' \
     | grep -vEi '(password|secret|api[-_]?key)s?[-_]?(ref|file|name|denylist)[[:space:]]*[:=]' \
     | grep -vE ':[[:space:]]*(password|secret|api[-_]?key)[a-zA-Z-]*[[:space:]]*[:=][[:space:]]*\$\{[A-Z][A-Z0-9_]*(:[^}]*)?\}' \
     || true)"

# ---------------------------------------------------------------------------------------
# 2. password-ref must point at an env var NAME, never contain a value. The env-var token
#    only needs to FOLLOW `password-ref:` — it may be trailed by prose (a markdown table
#    cell, a closing backtick, an inline comment), so match the name + a non-identifier
#    boundary rather than end-of-line (docs legitimately embed `password-ref: ENGINE_A_PASSWORD`).
report "every password-ref is a bare env-var name" \
  "$(git grep -nE 'password-ref[[:space:]]*[:=]' -- $SELF \
     | grep -vE 'password-ref[[:space:]]*[:=][[:space:]]*[A-Z][A-Z0-9_]*([^A-Za-z0-9_]|$)' \
     || true)"

# ---------------------------------------------------------------------------------------
# 3. ENGINE_*_PASSWORD assigned a literal in shipping source. Tests, docker dev stack,
#    CI, docs and skills legitimately set the public dev credential; main code never may.
report "no ENGINE_*_PASSWORD literals in shipping source (backend main / frontend src)" \
  "$(git grep -nE 'ENGINE_[A-Z0-9_]*PASSWORD["'"'"']?[[:space:]]*[:=,]' -- \
        'backend/src/main' 'frontend/src' \
     | grep -vE 'password-ref[[:space:]]*[:=]' \
     || true)"

# ---------------------------------------------------------------------------------------
# 4. Credentials embedded in URLs: scheme://user:password@host — anywhere in the repo.
#    Excludes .mcp.json: the dockerized dev-tooling config, whose Postgres DATABASE_URI
#    default (inspector:inspector@postgres) is the SAME public dev fixture as the compose
#    stack — a dev/test credential, allowed like docker/ and CI (secrets there stay env-ref).
report "no user:password@ URLs anywhere tracked" \
  "$(git grep -nE '[a-z][a-z0-9+.-]*://[^/@[:space:]"'"'"']+:[^/@[:space:]"'"'"']+@' -- \
        $SELF ':!*.lock' ':!*package-lock.json' ':!.mcp.json' \
     || true)"

# ---------------------------------------------------------------------------------------
# 5. Hardcoded Basic header literals in shipping source. Computed tokens
#    (btoa(user + ':' + pw), TestEngines.basicAuth(...)) don't match — only a literal
#    base64 blob after "Basic " does.
report "no hardcoded 'Authorization: Basic <base64>' literals in shipping source" \
  "$(git grep -nE '(Basic|BASIC)[[:space:]]+[A-Za-z0-9+/]{16,}={0,2}' -- \
        'backend/src/main' 'frontend/src' \
     || true)"

# ---------------------------------------------------------------------------------------
# 6. Private keys never belong in the repo, tracked anywhere.
report "no private-key blocks anywhere tracked" \
  "$(git grep -nE 'BEGIN[[:space:]](RSA|EC|DSA|OPENSSH|PGP)?[[:space:]]?PRIVATE KEY' -- $SELF \
     || true)"

# ---------------------------------------------------------------------------------------
echo
if [[ $FAILURES -gt 0 ]]; then
  echo "security-audit: ${FAILURES} check(s) FAILED — the env-ref secrets doctrine" \
       "(CLAUDE.md 'Iron rules', OPERATIONS §7) forbids committed credentials."
  echo "If a finding is a rotated/leaked real secret: rotate it FIRST, then clean history."
  exit 1
fi
echo "security-audit: all checks passed."
