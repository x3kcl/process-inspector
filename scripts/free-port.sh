#!/usr/bin/env bash
# free-port.sh <desired-port> [window] [avoid-port...]
#
# Prints the first free TCP port at-or-after <desired-port>, checked on 127.0.0.1,
# scanning at most [window] (default 10) consecutive ports and skipping any port
# listed in [avoid-port...]. A port counts as free when nothing accepts a
# connection on it locally — the same condition that would make `docker compose up`
# (or any other bind) fail with "port is already allocated".
#
# Used by ci.yml/nightly.yml's "Resolve the slot port namespace" steps so a runner
# slot's fixed port block (docker/ci-runner/docker-compose.yml) survives a port on
# that host being permanently squatted by something else (e.g. issue #163/#288 —
# an unrelated project's container holding slot s1's engine-B port) without needing
# a manual slot-block reassignment every time it happens. The window is intentionally
# small and scoped to the CALLER's own slot block (each slot's ports live in a
# distinct hundred/ten band — see docker/ci-runner/docker-compose.yml's header
# comment) so a fallback pick here can never wander into a different slot's range
# and create a NEW collision between two concurrently running jobs. The avoid-list
# is how a caller resolving several sibling ports in the same step (e.g. engine A/B/
# 7/legacy) stops two of them from independently landing on the same free port.
#
# Exits 1 (message on stderr) if no free, unavoided port exists in the window — the
# caller should let that hard-fail the job rather than silently proceeding on a bad
# guess.
set -euo pipefail

desired="${1:?usage: free-port.sh <desired-port> [window] [avoid-port...]}"
window="${2:-10}"
shift $(( $# >= 2 ? 2 : 1 ))
avoid=("$@")

is_avoided() {
  local port="$1" a
  for a in "${avoid[@]+"${avoid[@]}"}"; do
    [[ "$a" == "$port" ]] && return 0
  done
  return 1
}

is_free() {
  # bash's /dev/tcp pseudo-device: connecting succeeds only if something is
  # listening. Run in a subshell so the fd is closed automatically either way.
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

for ((offset = 0; offset < window; offset++)); do
  port=$((desired + offset))
  if ! is_avoided "$port" && is_free "$port"; then
    if ((offset > 0)); then
      echo "free-port: $desired unavailable, falling back to $port" >&2
    fi
    echo "$port"
    exit 0
  fi
done

echo "free-port: no free TCP port in [$desired, $((desired + window - 1))] (avoiding: ${avoid[*]+"${avoid[*]}"})" >&2
exit 1
