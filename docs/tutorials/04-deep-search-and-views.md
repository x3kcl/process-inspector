# Tutorial 04 — Deep search & views: filters, the depth wall, saved and team views

**Sign in as:** `viewer` (password `dev`) at <https://pi.naumann.cloud> for steps 1–7;
step 8 (publish to team) needs `operator`.

**You will learn:** how search state lives in the URL, how deep paging stays honest at its
depth cap, and how private, system, and team views relate.

## Steps

1. Open **`/search`**. Before any search runs you see the zero state ("Run a search to see
   process instances.") and your recent searches. Note the **Search button is disabled**
   while every filter is blank — with the reason in its tooltip: an empty search would send
   nothing, and a no-op that looks like a search would be a lie.
2. **Build a search.** In the rail: tick **Status → "Failed (dead-letter)"** and
   **"Failing (retries left)"**. The facet counts next to the chips preview the result
   sizes (with `≥` when derived from a capped scan). Add **Definition key** =
   `demoFailingPayment`. Click **Search**.
3. **Read below the form**: the **compiled-criteria echo** restates what ran — and makes
   the combination rule visible: AND between categories, OR within one (Failed OR Failing,
   AND the definition). Expand **"As cURL"**: the equivalent `POST /api/search` — every
   search is scriptable; the UI is an affordance over the API.
4. **Read the grid toolbar**: the row-count label plus "· as of HH:MM" — a snapshot stamp,
   not a live feed. Auto-refresh is opt-in and suspends while rows are selected (a grid
   must never mutate under your selection). The Engine column badges are
   environment-colored; children of call activities are marked "↳ child" so
   identically-keyed tree rows stay distinguishable.
5. **Go deep.** Broaden the search (clear the definition key, keep the statuses, or search
   Status = Active across both engines) and sort by **Start time (newest first)**. If more
   rows match than the page holds, a **"Load more"** button appears under the grid; each
   click extends the *globally sorted merged stream* — deep paging never silently reorders
   what you already saw. After the first Load more, note the seam: *"Loaded more as of
   HH:MM — newer instances won't appear until Refresh."* The chain is a point-in-time
   snapshot, by design.

   > **Demo scale caveat:** the seeded data set is small; with only a few dozen matches the
   > first page may hold everything and Load more never appears. The mechanics are
   > identical at 100k rows — that is what they exist for.

6. **Know the depth wall.** Past a per-engine paging depth cap, Load more stops with an
   explicit note — *"Reached the paging depth on at least one engine"* — and offers
   **"Continue by narrowing to started before HH:MM"**: one click restates the search with
   a time bound so the next stretch of the stream is reachable. Rows beyond the wall are
   *named as possibly existing*, never silently dropped; a count produced at the wall is a
   lower bound. (You will rarely hit the wall on the demo; you will on a 5M-row prod
   engine.)
7. **Save a view.** With your search on screen, open **"Save current view…"** in the rail,
   name it (e.g. `My failing payments`), **Save**. Now open the triage landing: your view
   renders under **Saved views** beside the system views (*Failed (all engines)*, *Failed
   in the last hour*, *Suspended > 24h (by start time)* — note how each system view's name
   states its honest predicate). A view is a named URL: clicking replays the exact search;
   relative windows re-materialize at click time. Saving the same name replaces it; views
   are per-user and server-backed — they follow you across browsers.
8. **Publish to team** (sign in as `operator`). Re-run a search worth institutionalizing,
   save it, then **"Publish to team…"**: publishing snapshots the view into the governed
   team store (a *copy* — later edits to your private view don't leak into canon) and is
   scope-gated: you can only publish over engines your OPERATOR scope covers. Team views
   render with a **TEAM** tag for everyone whose scope overlaps. **Unpublishing demands a
   reason ≥10 from every caller — the author included** — and lands in the operations log:
   removing team canon is a moderation act, not housekeeping. (Try unpublishing your own
   test view to see it; leave the demo tidy.)
9. **Share by URL** — there is deliberately no "share" button: the address bar *is* the
   share. Any view chip, any drill-through, any grid state — copy the URL and the recipient
   sees your exact scope.

## What you learned

- URL primacy: every search state is a URL; views, recents, and drills are all names for
  URLs.
- The criteria echo and cURL teach the real query semantics instead of hiding them behind a
  query language the engine couldn't honestly execute.
- Deep paging is a sorted, snapshot-stamped stream with an honest depth wall and a concrete
  "narrow here" next move — never fake global pagination, never a silent subset.
- Private views are yours; system views ship with honest predicate names; team views are
  governed canon with audited, reason-bearing moderation.

**Next:** [05 — Instance surgery](05-instance-surgery.md).
