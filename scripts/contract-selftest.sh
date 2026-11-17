#!/usr/bin/env bash
#
# contract-selftest.sh — pins the ci-nightly target of ci/contract.sh (#641): the target exists, an
# unknown part exits 2, and every nightly.yml job is a ci-nightly part that its job runs through the
# gate (`gate.sh ci-nightly` with `CI_PART: <job id>`). Text checks only: no part is run. ci-light lint runs it.
#
# supply-chain is the one exception: its logic is marketplace actions (.github/actions/supply-chain:
# buildx, Trivy, SBOM), which a shell contract cannot run.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
fail=0
no() { echo "contract-selftest: $*" >&2; fail=1; }
wf=.github/workflows/nightly.yml

out="$(CI_PART=no-such-part bash ci/contract.sh ci-nightly 2>&1)"; rc=$?
[ "$rc" = 2 ] || no "an unknown ci-nightly part exited $rc, want 2"
grep -q "is not a part of ci-nightly" <<< "$out" || no "ci/contract.sh has no ci-nightly target: $out"

for job in $(sed -n '/^jobs:/,$ s/^  \([a-z0-9-]*\):$/\1/p' "$wf"); do
  [ "$job" = supply-chain ] && continue
  grep -q "ci-nightly:$job[|)]" ci/contract.sh || no "nightly job '$job' is not a ci-nightly part of ci/contract.sh"
  block="$(sed -n "/^  $job:\$/,/^  [a-z0-9-]*:\$/p" "$wf")"
  grep -q "gate.sh ci-nightly" <<< "$block" && grep -q "CI_PART: $job\$" <<< "$block" \
    || no "nightly job '$job' does not run 'gate.sh ci-nightly' with CI_PART: $job"
done
[ "$fail" = 0 ] && echo "contract-selftest: ok"
exit "$fail"
