#!/usr/bin/env bash
# Fold a coverage sample + sparkline series into a status.json file in place.
# The series is a separate file on the dashboard host (coverage-history.json) so a
# failed read never replaces it with an empty one. One point per new green ci run and
# one per new green nightly, capped at 30. Missing artifacts leave status.json as-is.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

status="${1:?status json path}"
DASH_HOST="${PI_DASH_HOST:-flapci@172.16.62.253}"
DASH_DIR="${PI_DASH_DIR:-~/pi-ci-dashboard}"
remote="$DASH_DIR/html/coverage-history.json"

point="$(bash scripts/ci-coverage-sample.sh 2>/dev/null || echo '{}')"
if ! jq -e . >/dev/null 2>&1 <<<"$point"; then point='{}'; fi

if ! hist="$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$DASH_HOST" \
  "cat $remote 2>/dev/null || echo '[]'")"; then
  echo "coverage attach: history unreadable — left status.json unchanged" >&2
  exit 0
fi
if ! jq -e 'type == "array"' >/dev/null 2>&1 <<<"$hist"; then hist='[]'; fi

tmp="$(mktemp)"
series="$(jq -c \
  --argjson point "$point" \
  --argjson hist "$hist" \
  --argjson now "$(date +%s)" \
  '
    def has_number: (.backendLine != null) or (.frontendLine != null);
    (if ($point | has_number) and ($point.ciRunId != null)
          and ([ $hist[] | select(.source == "ci" and .ciRunId == $point.ciRunId) ] | length) == 0
        then [{source:"ci", at:$now, ciRunId:$point.ciRunId,
               backendLine:$point.backendLine, frontendLine:$point.frontendLine}]
        else [] end) as $ciPoint
    | (if ($point | has_number) and ($point.nightlyRunId != null)
          and ([ $hist[] | select(.source == "nightly" and .nightlyRunId == $point.nightlyRunId) ] | length) == 0
        then [{source:"nightly", at:$now, nightlyRunId:$point.nightlyRunId,
               backendLine:$point.backendLine, frontendLine:$point.frontendLine}]
        else [] end) as $nightPoint
    | ($hist + $ciPoint + $nightPoint)[-30:]
  ' <<<"$hist")" || { rm -f "$tmp"; exit 0; }

printf '%s\n' "$series" > "$tmp"
scp -o BatchMode=yes -o ConnectTimeout=10 "$tmp" "$DASH_HOST:$remote" >/dev/null 2>&1 || true

jq --argjson point "$point" --argjson series "$series" \
  '. + {coverage: ($point + {series: $series})}' "$status" > "$tmp.status" \
  && mv "$tmp.status" "$status"
rm -f "$tmp"
