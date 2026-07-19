# Tutorial 08 — Handover & audit: notes, tickets, the shift report, and who really did what

**Sign in as:** `responder` (password `dev`) at <https://pi.naumann.cloud>. Reading audit
surfaces is VIEWER-floor; adding notes needs RESPONDER.

**You will learn:** the HANDOVER half of the incident loop — leaving a trail the next shift
can act on, and reading the trail the last shift left, in the one place attribution is
actually true.

## Steps

1. **Start on an instance you touched** (any instance from tutorials 02/05 — find it via
   the omnibox or your recent searches). Open the **Audit & Notes** tab.
2. **Read the caveat banner first**: *"Engine-side history attributes these actions to the
   shared service account — this log is the authoritative WHO."* This is the single most
   important audit fact in the product: the BFF calls every engine with one machine
   account, so Flowable's own history tables (`ACT_HI_*`) will forever claim
   `inspector-svc` did everything. Who actually moved the token lives **here**, with name,
   reason, payload and outcome. Investigations start in this tool, not in the engine DB.
3. **Read the action history**: one row per corrective action on this instance — when,
   actor, action, outcome, reason. Expand a `payload` to see the exact recorded change
   (variable edits carry old *and* new values — the audit trail is why a plain variable
   edit is `RECOVERABLE`).
4. **Leave a handover note.** In **Notes**, write what the next shift needs — the
   placeholder shows the genre: *"do NOT retry — double-books; tax-service fix ETA 9am"*.
   Click **Add note**. Notes are per composite ID, authored and timestamped, and flagged
   by a marker on the instance's grid row — the next person searching this instance sees
   there is a note before they act.
5. **Copy for ticket.** In the vitals header, click **"copy for ticket"** and paste
   somewhere: composite ID, definition + version, status, exception first line, failure
   time, the deep link — and the latest note with a one-line actions-taken summary. One
   click instead of hand-assembled ticket text; timestamps in machine-facing text are
   always UTC ISO-8601.
6. **Open the global view**: **"Ops log"** in the header (`/audit`) — the same caveat
   banner, then every audited action fleet-wide. Filter by **Actor** = `responder` (you),
   or by an **Action** (`retry-job`, `edit-variable`, `bulk:…`), a **Ticket**, or a
   **Since** timestamp. Bulk jobs appear as one envelope row plus one row per item.
   Actions taken under break-glass wear a `break-glass` badge — loud by design.
7. **Produce your handover.** Click **"My shift"** — your own actions since shift start
   (last 8 h) — then **"Copy shift report"**: a plain-text export, UTC timestamps, with
   **UNKNOWN outcomes grouped first under NEEDS VERIFICATION**. That ordering is the
   handover contract: the next shift's first job is resolving your unknowns (Verify now in
   the operations drawer), not admiring your successes. **"Export CSV"** streams the same
   filtered view for spreadsheets — skeleton columns only; payload bodies stay in the app,
   role-gated.
8. **Know the correlation trail.** Every error the UI surfaces carries a `requestId` — the
   same correlation id stamped on the audit row and on every BFF log line for that request.
   When you escalate an Inspector problem (as opposed to an engine problem), quote it: it
   ties your click to the exact backend evidence.

## What you learned

- The BFF audit log is the **golden master for human attribution** — the engine's own
  history genuinely cannot tell you who acted, and the UI says so wherever it matters.
- Handover is a first-class surface: notes travel with the instance, copy-for-ticket and
  the shift report are one-click artifacts, and UNKNOWN-first ordering makes the report an
  action list, not a diary.
- Reasons, tickets and payloads make the audit trail *forward-useful* (it is the substrate
  for future remediation playbooks), not just retrospective compliance.
- requestId is the bridge between what you saw and what the operators of the Inspector can
  find in its logs.

**Done.** Back to the [Product Guide](../PRODUCT-GUIDE.md) — or start the loop again at
[01 — Triage first look](01-triage-first-look.md) with a colleague.
