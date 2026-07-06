---
name: live-ui-sse
description: The pattern for pushing live server→browser updates with Server-Sent Events — an SseEmitter registry fed by Spring events + a periodic snapshot probe, and a React EventSource hook. Read before adding any live/auto-refreshing UI (engine health badges, bulk-action progress, instance watch); do NOT reach for WebSockets, MQTT, or per-component polling.
---
# Live UI updates via SSE (process-inspector)

*Ported from the flap `live-ui-sse` skill; frontend adapted from Thymeleaf to React.*

When a surface needs to update live — engine health badges, bulk-operation per-item progress,
"watch this instance" auto-refresh — use **Server-Sent Events**, never WebSockets or MQTT. The
flow is one-directional (server→browser: commands go over normal POSTs), SSE auto-reconnects,
works over plain HTTP through the Vite proxy, and needs no broker.

## Backend pattern (Spring Boot)
1. **Emitter registry** — a `@Service` holding `CopyOnWriteArrayList<SseEmitter>`; `subscribe()`
   creates `SseEmitter(30 min)` and registers `onCompletion/onTimeout/onError` to drop it. Do
   NOT push an initial event on connect (the client fetches its own first state; a buffered
   early send gets replayed by Spring after the handler returns → broken-pipe noise).
2. **Bridge domain events** — `@EventListener` on events you already publish
   (`EngineHealthChangedEvent`, `BulkItemCompletedEvent`, …) → `send(eventName, payload)` to
   every emitter.
3. **`send()` must never throw** — it runs on the publishing thread (could be the health
   scheduler or a bulk executor). Guard the whole loop; on a failed write just drop that
   emitter (do NOT `complete()` an errored emitter — it re-flushes and throws a secondary
   `AsyncRequestNotUsableException`). `synchronized` so concurrent events don't interleave.
4. **Poll-style data → ONE shared periodic push** — engine health must be actively probed;
   the existing `EngineHealthService` scheduler builds the snapshot once and broadcasts it,
   **returning early when there are no emitters**. N browsers share one probe; never let each
   browser poll each engine.
5. **Controller** — `@GetMapping(value="/api/stream", produces=TEXT_EVENT_STREAM_VALUE)
   SseEmitter stream()`. Secure like any API route. Swallow the benign
   `AsyncRequestTimeoutException` in the global exception handler (it's just a reconnect).

## Frontend pattern (React)
- One `useEventSource(url)` hook owning a single `EventSource` for the app (context-provided),
  with `addEventListener('<event>', …)` per event kind. Components subscribe to the events
  they care about — never one EventSource per component.
- First paint: `fetch()` the current state, THEN attach the stream. Flip a live/offline
  indicator on `onopen`/`onerror` (EventSource auto-reconnects); `close()` on unmount.

## Per-user / bursty signals — push a SIGNAL, not the data
If a payload is per-user (e.g. "my running bulk operations") or bursts, send a payload-less
`changed` event; each browser refetches its OWN small JSON. Debounce client-side (~500ms).
Never compute per-subscriber payloads server-side in the broadcast loop.
