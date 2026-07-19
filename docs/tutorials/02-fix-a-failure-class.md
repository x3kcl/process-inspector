# Tutorial 02 — Fix a failure class: group retry with per-item outcomes

**Sign in as:** `responder` (password `dev`) at <https://pi.naumann.cloud>. RESPONDER is
the L1/L2 rung: tier-0 verbs, unstick, notes — and the bulk retry doors used here.

**You will learn:** how one error-class card becomes one tracked bulk job with per-item
outcomes — and why "dispatched ok" is not the same claim as "fixed".

## Steps

1. On the triage landing, pick an error-class card with a non-zero **DLQ** count (e.g. the
   seeded `demoFailingPayment` divide-by-zero class). Note the current instance count and
   which engines/versions it spans.
2. Under a specific version count, click **"Retry group"**. If the definition has several
   deployed versions you will also see **"Retry group (all versions)"** — that one covers
   the whole definition key in a single job. Use the single-version button for now.
3. **Read the modal before acting** — "Retry group — run every failed step in this error
   class again". Observe:
   - the restated scope (signature, definition + version, engine, member count);
   - the reason field: *"Why are you doing this? (required, 10+ characters — saved to the
     audit trail on every item)"* — mandatory at every bulk door, dev or prod;
   - what you do NOT send: an instance list. The browser submits the group's
     *coordinates*; the BFF re-resolves the members itself at dispatch, so a stale grid can
     never make you act on the wrong set.

   > **Prod difference:** on a prod-tagged engine you would additionally type the
   > **definition key** as a confirmation token — deliberately the key, not the count,
   > because the member list is re-resolved server-side and a typed count would attest a
   > stale number. The demo engines are dev, so no token is demanded.

4. Enter a reason (e.g. `tutorial 02 — retrying seeded divide-by-zero class`) and click
   **"Retry group — demoFailingPayment v1"**. The modal dispatches and the **Operations
   drawer** ("Operations" at the bottom edge) starts tracking the job live.
5. **Open the drawer and read the per-item report.** Each member instance is one row:
   expect mostly `ok` — meaning *the dead-letter job moved back to the executable queue
   with retries reset*. Read the drawer's own callout: for retry-job, `ok` = re-queued,
   **not** healed. If two responders raced, some rows may read `skipped (already
   resolved)` — enriched with who handled it, from the audit log.
6. **Watch the aggregate readout** ("N of M dispatched · ok/failed/skipped/unknown"). The
   job state goes `PENDING → RUNNING → COMPLETED`. Progress arrives over a live stream —
   no reload needed.
7. **Now verify honestly.** Go back to the landing and Refresh. The seeded failure divides
   by a zero variable, so the retried jobs will fail again once their retries re-exhaust:
   over the following minutes the card **refills**. This is the point: a retry is the right
   verb only when the *cause* is gone (a transient outage, a fixed downstream). When the
   cause is data, fix the data first — that is tutorial 05's arc — or the class boomerangs.
8. Optional: open one member instance and check its **Audit & Notes** tab — your bulk item
   is there, with your name, your reason, and the outcome. The envelope and every item are
   separately audited.

## What you learned

- The error-class door sends coordinates, never a browser-enumerated ID list; the server
  re-resolves membership at dispatch. Refusals are honest and named (stale group → "refresh
  the landing"; drained group → no zero-item job; over 200 members → the filter-scope door
  is the answer).
- Every bulk run is a persisted tracked job with per-item outcomes that survive refresh and
  restart; the drawer, not a toast, is the record.
- `ok` states exactly what the call guarantees (a queue move) and nothing more. Verifying
  the *fix* means watching the class, not the dispatch — the incident ledger (tutorial 03)
  exists precisely because a drained DLQ is not a proven fix.

**Next:** [03 — Incident lifecycle](03-incident-lifecycle.md).
