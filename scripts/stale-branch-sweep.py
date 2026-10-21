#!/usr/bin/env python3
"""List remote branches that are safe to delete (#451). Does not delete.

A candidate's tip is older than 30 days, the name is not main, release*, or
review/*, and its newest pull request is MERGED or CLOSED. Open PRs and
branches with no pull request are kept.

    python3 scripts/stale-branch-sweep.py
    python3 scripts/stale-branch-sweep.py --comment
"""

import json
import subprocess
import sys
import urllib.parse
from datetime import datetime, timedelta, timezone

REPO = "x3kcl/process-inspector"


def gh(path: str):
    out = subprocess.check_output(["gh", "api", path], text=True)
    return json.loads(out)


def gh_pages(path: str):
    page = 1
    items = []
    while True:
        batch = gh(f"{path}{'&' if '?' in path else '?'}per_page=100&page={page}")
        if not batch:
            break
        items.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return items


def main() -> int:
    cutoff = datetime.now(timezone.utc) - timedelta(days=30)
    branches = gh_pages(f"repos/{REPO}/branches")
    candidates = []
    for branch in branches:
        name = branch["name"]
        if name == "main" or name.startswith("release") or name.startswith("review/"):
            continue
        encoded = urllib.parse.quote(name, safe="")
        commit = gh(f"repos/{REPO}/commits/{encoded}")
        committed = datetime.fromisoformat(commit["commit"]["committer"]["date"].replace("Z", "+00:00"))
        if committed > cutoff:
            continue
        prs = gh(
            f"repos/{REPO}/pulls?head=x3kcl:{encoded}&state=all&per_page=1"
        )
        state = prs[0]["state"].upper() if prs else "NONE"
        # pulls API uses "closed" for both merged and closed; merged_at distinguishes.
        if prs and prs[0].get("merged_at"):
            state = "MERGED"
        elif state == "CLOSED":
            state = "CLOSED"
        if state in {"MERGED", "CLOSED"}:
            candidates.append((name, committed.date().isoformat(), state))

    lines = [
        f"Stale-branch candidates (tip older than {cutoff.date().isoformat()}, PR merged or closed).",
        "Kept: main, release*, review/*, open PRs, branches with no PR, anything newer.",
        "Deletion is manual.",
        "",
    ]
    if not candidates:
        lines.append("None.")
    else:
        lines.extend(f"{name}\t{day}\t{state}" for name, day, state in candidates)
        lines.append("")
        lines.append(f"{len(candidates)} candidate(s).")
    report = "\n".join(lines) + "\n"
    sys.stdout.write(report)
    if "--comment" in sys.argv:
        subprocess.run(
            ["gh", "issue", "comment", "451", "--repo", REPO, "--body", report],
            check=True,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
