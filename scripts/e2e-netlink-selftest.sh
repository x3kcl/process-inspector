#!/usr/bin/env bash
#
# e2e-netlink-selftest.sh — pins the #698 fix: the hermetic e2e browser must not see the host's
# netlink stream. CI slots share the host network, so every docker bridge another job creates is an
# address change to Chromium, which aborts in-flight requests with net::ERR_NETWORK_CHANGED (every
# failed-attempt trace of the red runs carries it). Checks that frontend/e2e/no-netlink.c, preloaded,
# refuses a NETLINK_ROUTE socket and nothing else, that the contract's e2e part builds and exports it,
# and that playwright.config.ts preloads it into the browser. ci/contract.sh e2e runs it.
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

block="$(sed -n '/^e2e() {$/,/^}$/p' ci/contract.sh)"
grep -q 'e2e/no-netlink.c' <<< "$block" || no "ci/contract.sh e2e does not build e2e/no-netlink.c"
grep -q 'export PI_E2E_NO_NETLINK' <<< "$block" || no "ci/contract.sh e2e does not export PI_E2E_NO_NETLINK"
grep -q 'LD_PRELOAD: process.env.PI_E2E_NO_NETLINK' frontend/playwright.config.ts \
  || no "frontend/playwright.config.ts does not preload PI_E2E_NO_NETLINK into the browser"

[ "$fail" = 0 ] && echo "e2e-netlink-selftest: ok"
exit "$fail"
