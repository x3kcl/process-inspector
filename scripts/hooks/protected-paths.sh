#!/usr/bin/env bash
# [#644] PreToolUse: refuse edits of generated/frozen files. A broken hook exits 0.
set -u
input=$(cat || true)
python3 -c '
import json, os, re, subprocess, sys
try:
    data = json.loads(sys.stdin.read())
    ti = data.get("tool_input") or {}
    path = (ti.get("file_path") or "").replace("\\", "/")
    root = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
except Exception:
    sys.exit(0)
if not path or os.environ.get("PI_HOOK_BYPASS") == "1":
    sys.exit(0)
if path.startswith(root + "/"):
    path = path[len(root) + 1:]
msg = None
if path == "frontend/src/api/schema.d.ts":
    msg = "generated file — run `npm run gen:api` in frontend/"
elif path == "frontend/scripts/check-bpmn-watermark.mjs":
    msg = "bpmn.io watermark guard (R-GOV-05) is frozen"
elif re.fullmatch(r"backend/src/main/resources/db/migration/V[^/]*\.sql", path):
    tracked = subprocess.run(["git", "-C", root, "ls-files", "--error-unmatch", path],
                             capture_output=True).returncode == 0
    if tracked:
        msg = "Flyway migrations are immutable once committed — add a new version"
if msg:
    sys.stderr.write("protected path: " + path + " — " + msg + "\n")
    sys.exit(2)
' <<<"$input"
