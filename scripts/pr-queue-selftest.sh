#!/usr/bin/env bash
# Offline selftest for pr-queue.sh: a stubbed `curl` on PATH serves fixtures and logs every call.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir "$T/bin"
cat > "$T/bin/curl" <<'STUB'
#!/usr/bin/env bash
m=GET u=""
while [[ $# -gt 0 ]]; do
  case "$1" in -X) m="$2"; shift ;; http*) u="$1" ;; esac; shift
done
echo "$m $u" >> "$FIX/calls"
case "$u" in
  */actions/runs*) cat "$FIX/runs.json" ;;
  */issues/*/comments*) cat "$FIX/comments.json" ;;
  */issues/*/labels) echo '[]' ;;
  */pulls/*) cat "$FIX/pull.json" ;;
esac
STUB
chmod +x "$T/bin/curl"
export FIX="$T" PATH="$T/bin:$PATH" GITHUB_PERSONAL_ACCESS_TOKEN=selftest-token PQ_SLUG=o/r PQ_INTERVAL=0 PQ_TIMEOUT=0

fail=0
# fixture <ci-conclusion> <merged> <labelled>
fixture() {
  : > "$T/calls"
  local lab='[]'; [[ "$3" == true ]] && lab='[{"name":"queue"}]'
  echo "{\"head\":{\"sha\":\"abc\"},\"merged\":$2,\"labels\":$lab}" > "$T/pull.json"
  echo "{\"workflow_runs\":[{\"path\":\".github/workflows/ci.yml\",\"status\":\"completed\",\"conclusion\":\"$1\"}]}" > "$T/runs.json"
  echo '[{"body":"queue: batch red"}]' > "$T/comments.json"
}
# check <name> <want-exit> <want-label-call yes|no> <args...>
check() {
  local name="$1" want="$2" lab="$3"; shift 3
  out="$("$ROOT/scripts/pr-queue.sh" "$@" 2>&1)"; rc=$?
  got=no; grep -q '^POST .*/labels' "$T/calls" && got=yes
  if [[ $rc != "$want" || $got != "$lab" ]]; then
    echo "FAIL $name: exit $rc (want $want), label call $got (want $lab)"; fail=1
  fi
  if grep -q selftest-token <<<"$out"; then echo "FAIL $name: token echoed"; fail=1; fi
}
fixture success false false; check "green head is labelled" 0 yes 7
fixture failure false false; check "red head is refused" 1 no 7
fixture success true true;   check "merged exits 0" 0 no --watch 7
fixture success false false; check "unlabelled exits 1" 1 no --watch 7
fixture success false true;  check "waiting times out" 3 no --watch 7
fixture success false true;  check "status prints" 0 no --status 7
[[ $fail == 0 ]] && echo "pr-queue-selftest: ok"
exit $fail
