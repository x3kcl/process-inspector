# Tutorial 03 — Incident lifecycle: resolve, quiet, REGRESSED — and ack vs. resolve

**Sign in as:** `operator` (password `dev`) at <https://pi.naumann.cloud>. Resolve/reopen
are OPERATOR verbs; the list and detail are readable by any VIEWER.

**You will learn:** what the incident ledger remembers that Stage 0 forgets, what Resolve
actually claims, why a fresh resolve cannot instantly "regress", and when to acknowledge
instead.

## Steps

1. Open **"Incidents"** in the header (`/incidents`). The page is the **Incident Ledger**:
   one card per error signature, *persisted* — unlike Stage-0 cards, these survive a DLQ
   drain. Read the sections in order: **Regressed** (came back after a human said fixed),
   **Open**, **Quiet** ("Still open, but nothing observed recently — worth a second look
   before you assume it's fixed."), **Resolved** (collapsed), **Archived generations**
   (fingerprinted by an older normalizer version — kept, never silently re-bound).
2. **Open an OPEN incident** (the seeded failing classes from tutorial 02 will be here —
   ingestion piggybacks the background sampler, so a class appears within ~a minute of
   first failing). On the detail page, read top to bottom:
   - the lifecycle strip (state, first/last seen, regressions);
   - the **arrival-rate timeline** — occurrence samples over time. A spike is an outage; a
     slow trickle is a data-quality bug. A *truncated* sample renders visually distinct:
     it is a **floor, not a dip** (the scan was capped, not the failures fewer);
   - the per-engine × definition breakdown and the sample raw message;
   - **Episodes** — one row per open→resolve cycle with its duration: your per-episode
     time-to-resolution record;
   - **Recent bulk retries** — read-only: error-class bulk jobs matching this signature
     (your tutorial-02 job should be listed). The ledger never mutates engine state;
     remediation is dispatched from the Stage-0 card.
3. Click **"Search these instances"** — the live class in the grid, one click. Come back.
4. **Resolve it.** Click **Resolve** — "Resolve incident — close the current episode".
   Fill the reason (*required, 10+ characters — saved to the audit trail*), e.g.
   `tutorial 03 — practicing resolve; cause not actually fixed`. Note the checkbox:
   **"Also acknowledge on the live dashboard"** — and its explanation: *resolving here does
   NOT mute the Stage-0 triage card by itself*. Leave it **unchecked** this time. Confirm
   with **"Resolve incident"**.
5. **Observe the result honestly.** The incident moves to Resolved; the episode gets an
   end time, your name and reason. Now open the triage landing: the Stage-0 card is
   **still there** — resolve is a ledger claim, not noise control. This is the
   resolve-vs-ack distinction in one screen:
   - **Acknowledge** (Stage-0 card, also OPERATOR): "known noise — collapse the card";
     auto-resurfaces on growth/new version/expiry; deletable; no history.
   - **Resolve** (ledger): "we fixed the root cause" — permanent, audited, arms regression
     detection, stamps MTTR. The checkbox exists so that when you really have fixed it,
     you can do both in one dialog — as two separately-audited actions.
6. **Understand why it won't instantly regress.** The demo class is still failing (we
   resolved without fixing), yet the incident will *not* flip to REGRESSED on the next
   sampler cycle: regression is gated on a **post-resolve zero-state** — at least one cycle
   must first observe the class absent or at zero. Until then the ledger keeps updating
   last-seen and totals (the data stays honest) while the state waits. This kills the
   "zombie incident": a resolve during retry-lag can never bounce back seconds later. The
   real REGRESSED arc — fix → class drains → a zero cycle observed → failures reappear →
   REGRESSED + a new episode — needs a genuinely fixed-then-broken class, which the shared
   demo can't stage on demand; you now know what the alarm section means when you see it.
7. **Undo your practice resolve.** Since we resolved by mistake (deliberately), the honest
   verb is **Reopen** — "Reopen incident — undo a resolve", reason required, e.g.
   `tutorial 03 — resolve was practice, class is not fixed`. Reopen re-opens the *same*
   episode and does **not** count as a regression — a human undo is not the failure coming
   back. The incident returns to Open; the demo is tidy again.

## What you learned

- The ledger answers "what happened, when, did it come back" with persisted incidents,
  episodes (MTTR), and an arrival-rate timeline whose truncated points are floors — honesty
  markers survive end-to-end.
- Resolve is a human, audited, fleet-wide claim; REGRESSED is automatic but zero-state
  gated; Reopen is the human undo and deliberately not a regression; Quiet is derived at
  render, never stored.
- Ack mutes a card; Resolve claims a fix. The "Also acknowledge" checkbox is the one
  sanctioned bridge — explicit, opt-in, separately audited.

**Next:** [04 — Deep search & views](04-deep-search-and-views.md).
