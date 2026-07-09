#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S0 — K-WAY-MERGE DEEP-PAGING P0 WIRE-SHAPE SPIKE  (docs/KWAY-PAGING.md §6)
#
# Same method as docker/ (migration spike lineage): probe the REAL flowable-rest
# wire shape across the full version matrix — never trust a fact imported from a
# sibling endpoint. Run against the dockerized engines from
# docker/docker-compose.dev.yml (6.8.0 :8081/:8082, 7.1.0 :8083, 6.3.1 :8084).
#
#   ./docker/spike-kway-paging.sh              # all engines
#   ./docker/spike-kway-paging.sh 8081         # one port
#
# Read-only: issues only GET + POST /query (both idempotent reads). Seeds NOTHING
# and mutates NOTHING (iron rule: engine state is REST-only, and this only reads).
# Findings are transcribed into docs/KWAY-PAGING.md §6 with per-version evidence.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

AUTH="rest-admin:test"
CURL=(curl -s -u "$AUTH")
declare -A ENGINES=( [8081]=6.8.0 [8083]=7.1.0 [8084]=6.3.1 )
PORTS=( "${@:-8081 8083 8084}" )
# shellcheck disable=SC2206
[ $# -gt 0 ] && PORTS=( "$@" ) || PORTS=( 8081 8083 8084 )

hr() { printf '─%.0s' {1..78}; echo; }
py() { python3 -c "$1"; }

svc() { echo "http://localhost:$1/flowable-rest/service"; }

# ── #1 Historic-body sub-second granularity ─────────────────────────────────
# Does POST /query/historic-process-instances honour a sub-second startedBefore,
# or truncate/400 like the GET job params? (Decides if a startTime keyset is even
# constructible — the draft assumed whole-second, unproven on the POST body.)
probe_historic_body() {
  local p=$1 svc; svc=$(svc "$p")
  echo "  [1] POST /query/historic-process-instances  startedBefore=…T00:00:00.500Z"
  "${CURL[@]}" -X POST -H 'Content-Type: application/json' \
    -d '{"startedBefore":"2030-01-01T00:00:00.500Z","size":1,"sort":"startTime","order":"desc"}' \
    -w '      -> HTTP %{http_code}\n' -o /dev/null "$svc/query/historic-process-instances"
}

# ── #2 startTime echo precision + format ────────────────────────────────────
# Sub-second digits present? offset-form (+00:00) vs Z? (A keyset boundary can't
# be reconstructed without it; and R-SEM-23 needs Instant-parse if forms differ.)
probe_starttime_echo() {
  local p=$1 svc; svc=$(svc "$p")
  echo "  [2] historic startTime echo (GET, sort=startTime desc):"
  "${CURL[@]}" "$svc/history/historic-process-instances?size=1&sort=startTime&order=desc" \
    | py "import sys,json;d=json.load(sys.stdin);r=d.get('data') or [{}];print('      startTime =',r[0].get('startTime'),' total =',d.get('total'))"
  echo "  [2b] deadletter-jobs createTime echo (the failureTime source):"
  "${CURL[@]}" "$svc/management/deadletter-jobs?size=1&sort=createTime&order=desc" \
    | py "import sys,json;d=json.load(sys.stdin);r=d.get('data') or [{}];print('      createTime =',r[0].get('createTime'),' total =',d.get('total'))"
}

# ── #3 Offset stability of sort=startTime&order=desc ─────────────────────────
# Page the same window twice; report dup/skip. Same-second tie clusters counted
# (whole-second truncation) — these are exactly where a secondary tiebreak (the
# R-SEM-23 compositeId) earns its keep.
probe_offset_stability() {
  local p=$1 svc; svc=$(svc "$p")
  echo "  [3] offset stability (size=10 windows x6, twice) + same-second clusters:"
  scan() {
    local start=0 out=""
    while [ $start -lt 60 ]; do
      out="$out $("${CURL[@]}" "$svc/history/historic-process-instances?size=10&start=$start&sort=startTime&order=desc" \
        | py "import sys,json;print(' '.join(r['id'] for r in json.load(sys.stdin).get('data',[])))")"
      start=$((start+10))
    done
    echo "$out"
  }
  local s1 s2; s1=$(scan); s2=$(scan)
  local n1 u1; n1=$(echo "$s1"|wc -w); u1=$(echo "$s1"|tr ' ' '\n'|sort -u|grep -c .)
  echo "      run1 rows=$n1 uniq=$u1   identical-order run1==run2: $([ "$s1" == "$s2" ] && echo YES || echo NO)"
  "${CURL[@]}" "$svc/history/historic-process-instances?size=100&sort=startTime&order=desc" \
    | py "import sys,json,collections;d=json.load(sys.stdin).get('data',[]);c=collections.Counter(r['startTime'][:19] for r in d if r.get('startTime'));dupes={k:v for k,v in c.items() if v>1};print('      same-second clusters in top-100:',len(dupes),'| max cluster:',max(dupes.values()) if dupes else 0)"
}

# ── #4 DLQ default-order stability + sort=id honoured ────────────────────────
# The only candidate for a stable, resumable DLQ offset is sort=id&order=asc.
probe_dlq_order() {
  local p=$1 svc; svc=$(svc "$p")
  echo -n "  [4] deadletter-jobs sort=id&order=asc -> HTTP "
  "${CURL[@]}" -o /dev/null -w '%{http_code}\n' "$svc/management/deadletter-jobs?size=1&sort=id&order=asc"
}

# ── #5 failureTime (job createTime) sortability ─────────────────────────────
# Draft claimed jobs reject createTime as a sort field (400). VERIFY LIVE — and
# whether a 200 actually orders or silently ignores.
probe_job_sort() {
  local p=$1 svc; svc=$(svc "$p")
  local code; code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$svc/management/deadletter-jobs?size=1&sort=createTime&order=asc")
  echo -n "  [5] deadletter-jobs sort=createTime -> HTTP $code"
  if [ "$code" = "200" ]; then
    # Does ASC vs DESC genuinely flip? (else the param is silently ignored)
    local a d; a=$("${CURL[@]}" "$svc/management/deadletter-jobs?size=1&sort=createTime&order=asc" | py "import sys,json;r=json.load(sys.stdin).get('data') or [{}];print(r[0].get('createTime',''))")
    d=$("${CURL[@]}" "$svc/management/deadletter-jobs?size=1&sort=createTime&order=desc" | py "import sys,json;r=json.load(sys.stdin).get('data') or [{}];print(r[0].get('createTime',''))")
    if [ -n "$a" ] && [ "$a" != "$d" ]; then echo "  (ASC=$a != DESC=$d -> ORDERS, sortable)"; else echo "  (ASC==DESC or empty -> inconclusive/ignored)"; fi
  else echo "  (rejected -> failureTime UNSORTABLE on this version)"; fi
}

# ── #6 6.3 paging-param cliff ────────────────────────────────────────────────
# Does the version silently drop start/size (cf. the businessKeyLike cliff)?
probe_paging_cliff() {
  local p=$1 svc; svc=$(svc "$p")
  local i0 i1 i2
  i0=$("${CURL[@]}" "$svc/history/historic-process-instances?size=1&start=0&sort=startTime&order=desc" | py "import sys,json;r=json.load(sys.stdin).get('data') or [{}];print(r[0].get('id',''))")
  i1=$("${CURL[@]}" "$svc/history/historic-process-instances?size=1&start=1&sort=startTime&order=desc" | py "import sys,json;r=json.load(sys.stdin).get('data') or [{}];print(r[0].get('id',''))")
  i2=$("${CURL[@]}" "$svc/history/historic-process-instances?size=1&start=2&sort=startTime&order=desc" | py "import sys,json;r=json.load(sys.stdin).get('data') or [{}];print(r[0].get('id',''))")
  echo "  [6] paging cliff: start=0/1/2 distinct? $([ -n "$i0" ] && [ "$i0" != "$i1" ] && [ "$i1" != "$i2" ] && echo 'PAGES OK (no cliff)' || echo 'CLIFF — start ignored')"
}

# ── #7 Cost model at offset ──────────────────────────────────────────────────
# Time start=0 vs deep start to set the real per-engine depth cap. NOTE: the dev
# corpus is tiny (~100s of rows, TEST-STRATEGY §10 forbids seeding thousands) so
# this only rules OUT gross non-linearity; the cap number is set conservatively.
probe_cost() {
  local p=$1 svc; svc=$(svc "$p")
  echo "  [7] cost (size=10 wall-time): "
  for st in 0 50 100; do
    printf '      start=%-4s -> %ss\n' "$st" "$("${CURL[@]}" -o /dev/null -w '%{time_total}' "$svc/history/historic-process-instances?size=10&start=$st&sort=startTime&order=desc")"
  done
}

for p in "${PORTS[@]}"; do
  hr
  echo "ENGINE ${ENGINES[$p]:-?}  (port $p)"
  hr
  if ! "${CURL[@]}" -o /dev/null -w '' "$(svc "$p")/repository/deployments?size=1" 2>/dev/null; then
    echo "  !! unreachable — is docker-compose.dev.yml up?"; continue
  fi
  probe_historic_body "$p"
  probe_starttime_echo "$p"
  probe_offset_stability "$p"
  probe_dlq_order "$p"
  probe_job_sort "$p"
  probe_paging_cliff "$p"
  probe_cost "$p"
done
hr
echo "DONE. Transcribe findings into docs/KWAY-PAGING.md §6."
