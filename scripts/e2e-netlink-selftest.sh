#!/usr/bin/env bash
#
# e2e-netlink-selftest.sh — pins the #698 fix: the hermetic e2e browser must not see the host's
# netlink stream. CI slots share the host network, so every docker bridge another job creates is an
# address change to Chromium, which aborts in-flight requests with net::ERR_NETWORK_CHANGED (every
# failed-attempt trace of the red runs carries it). Checks that frontend/e2e/no-netlink.c, preloaded,
# refuses a NETLINK_ROUTE socket and nothing else, that the contract's e2e and e2e-real parts build and
# export it (#702), and that both Playwright configs preload it into the browser. ci/contract.sh e2e runs it.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
fail=0
no() { echo "e2e-netlink-selftest: $*" >&2; fail=1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

probe='import socket
try:
    socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0); print("netlink-open")
except OSError:
    print("netlink-refused")
socket.socket(socket.AF_INET, socket.SOCK_STREAM); print("inet-open")'

if cc -shared -fPIC -o "$tmp/nn.so" frontend/e2e/no-netlink.c -ldl 2>"$tmp/cc.err"; then
  [ "$(python3 -c "$probe" | tr '\n' ' ')" = "netlink-open inet-open " ] \
    || no "without the shim this host cannot open a NETLINK_ROUTE socket — the probe proves nothing here"
  got="$(LD_PRELOAD="$tmp/nn.so" python3 -c "$probe" | tr '\n' ' ')"
  [ "$got" = "netlink-refused inet-open " ] || no "preloaded shim: want 'netlink-refused inet-open', got '$got'"
else
  no "frontend/e2e/no-netlink.c does not build: $(head -3 "$tmp/cc.err")"
fi

# #702: one helper builds the shim; the hermetic e2e part and the nightly e2e-real part both call it,
# and both Playwright configs preload it (e2e-real brings its own compose engines up = the churn).
fn() { sed -n "/^$1() {\$/,/^}\$/p" ci/contract.sh; }
helper="$(fn e2e_no_netlink)"
grep -q 'e2e/no-netlink.c' <<< "$helper" || no "ci/contract.sh e2e_no_netlink does not build e2e/no-netlink.c"
grep -q 'export PI_E2E_NO_NETLINK' <<< "$helper" || no "ci/contract.sh e2e_no_netlink does not export PI_E2E_NO_NETLINK"
for part in e2e nightly_e2e_real; do
  fn "$part" | grep -qE '^[[:space:]]*e2e_no_netlink$' || no "ci/contract.sh $part does not build the shim (e2e_no_netlink)"
done
for cfg in frontend/playwright.config.ts frontend/e2e-real/playwright.config.ts; do
  grep -q 'LD_PRELOAD: process.env.PI_E2E_NO_NETLINK' "$cfg" \
    || no "$cfg does not preload PI_E2E_NO_NETLINK into the browser"
done

[ "$fail" = 0 ] && echo "e2e-netlink-selftest: ok"
exit "$fail"
