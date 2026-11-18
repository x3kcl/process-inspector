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
[[ -f "$FIX/down" ]] && exit 22
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
  : > "$T/calls"; rm -f "$T/down"
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
fixture success false true; touch "$T/down"; check "API error exits 2" 2 no --watch 7
fixture success false false; touch "$T/down"; check "API error refuses with 2" 2 no 7
fixture success false false; check "PR then --status never labels" 0 no 7 --status
fixture success false false; check "PR then --watch watches" 1 no 7 --watch
fixture success false false; check "unknown flag exits 2, no call" 2 no 7 --bogus
[[ -s "$T/calls" ]] && { echo "FAIL unknown flag made an API call"; fail=1; }
grep -q '^usage:' <<<"$out" || { echo "FAIL unknown flag printed no usage line"; fail=1; }
fixture success false false; check "two mode flags exit 2" 2 no --watch 7 --status
# queue refusal comment (label stays): a marker for the CURRENT head ends --watch with 1 and prints the comment
fixture success false true; echo '[{"body":"merge queue: head abc is not RED-first\nmq-red-first:abc"}]' > "$T/comments.json"
check "red-first marker for head exits 1" 1 no --watch 7
grep -q 'not RED-first' <<<"$out" || { echo "FAIL red-first comment not printed"; fail=1; }
fixture success false true; echo '[{"body":"frozen RED file\nmq-red-frozen:abc"}]' > "$T/comments.json"
check "red-frozen marker for head exits 1" 1 no --watch 7
fixture success false true; echo '[{"body":"old\nmq-red-first:def"}]' > "$T/comments.json"
check "marker for an older head keeps watching" 3 no --watch 7
fixture success false true; echo '[{"body":"x\nmq-red-first:abc"}]' > "$T/comments.json"
check "status shows refusal" 0 no --status 7
grep -q 'refused: mq-red-first:abc' <<<"$out" || { echo "FAIL status lacks refused marker"; fail=1; }
# #780: every pull_request-event run on the head gates the label; the queue's mq-red / mq-verdict refusals end --watch
# lanes <ci-conclusion> <supply-chain-conclusion> [<supply-chain-status>]: two pull_request runs plus a non-gating dispatch run
lanes() {
  printf '{"workflow_runs":[{"id":1,"name":"ci","event":"pull_request","path":".github/workflows/ci.yml","status":"completed","conclusion":"%s"},{"id":2,"name":"supply-chain-pr","event":"pull_request","path":".github/workflows/supply-chain-pr.yml","status":"%s","conclusion":"%s"},{"id":3,"name":"nightly","event":"workflow_dispatch","path":".github/workflows/nightly.yml","status":"completed","conclusion":"failure"}]}' \
    "$1" "${3:-completed}" "$2" > "$T/runs.json"
}
fixture success false false; lanes success failure; check "ci green + supply-chain-pr red is refused" 1 no 7
grep -q 'supply-chain-pr' <<<"$out" || { echo "FAIL red lane not named"; fail=1; }
fixture success false false; lanes success success; check "all pull_request lanes green is labelled" 0 yes 7
fixture success false false; lanes success null in_progress; check "a lane in progress is not green yet" 1 no 7
fixture success false true; lanes success failure; echo '[{"body":"merge queue: head abc is red on PR lane supply-chain-pr — u. mq-red:abc"}]' > "$T/comments.json"
check "mq-red marker for head exits 1" 1 no --watch 7
grep -q 'red on PR lane supply-chain-pr' <<<"$out" || { echo "FAIL mq-red comment not printed"; fail=1; }
fixture success false true; echo '[{"body":"merge queue: lane-red\n<!-- mq-verdict {\"v\":1,\"code\":\"lane-red\",\"head\":\"abc\",\"state\":\"refused\"} -->"}]' > "$T/comments.json"
check "mq-verdict refused for head exits 1" 1 no --watch 7
grep -q 'lane-red' <<<"$out" || { echo "FAIL verdict comment not printed"; fail=1; }
fixture success false true; echo '[{"body":"old\nmq-red:def"}]' > "$T/comments.json"
check "mq-red for an older head keeps watching" 3 no --watch 7
fixture success false true; echo '[{"body":"x\n<!-- mq-verdict {\"v\":1,\"code\":\"lane-red\",\"head\":\"def\",\"state\":\"refused\"} -->"}]' > "$T/comments.json"
check "verdict for an older head keeps watching" 3 no --watch 7
fixture success false true; echo '[{"body":"x\n<!-- mq-verdict {\"v\":1,\"code\":\"lane-red\",\"head\":\"abc\",\"state\":\"refused\"} -->"}]' > "$T/comments.json"
check "status shows verdict code" 0 no --status 7
grep -q 'refused: lane-red' <<<"$out" || { echo "FAIL status lacks verdict code"; fail=1; }
fixture success false true; echo '[{"body":"x\nmq-red:abc"}]' > "$T/comments.json"
check "status shows mq-red" 0 no --status 7
grep -q 'refused: mq-red:abc' <<<"$out" || { echo "FAIL status lacks mq-red marker"; fail=1; }
[[ $fail == 0 ]] && echo "pr-queue-selftest: ok"
exit $fail
