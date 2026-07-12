# Process Inspector — Web (frontend)

The **web tier** of the Flowable **Process Inspector**: an nginx image serving the React/TypeScript
single-page app and reverse-proxying `/api` to the BFF. It is the UI a support engineer opens
during an incident to **find, diagnose, and fix** process-instance problems across many
Flowable engines from one screen.

This image is UI-only — it pairs with
[`x3kcl/process-inspector-bff`](https://hub.docker.com/r/x3kcl/process-inspector-bff)
(the Spring Boot API tier), which it proxies to. Source & full docs:
**https://github.com/x3kcl/process-inspector**

## What's inside

- **Three-stage UI** — a triage landing (cross-engine error groups), search + results grid,
  and a full-page, deep-linkable instance detail with the BPMN diagram synced to the job
  lanes and variable ledger.
- **AG Grid Community** results grid, **bpmn-js** `NavigatedViewer` for diagrams, and a
  **CodeMirror 6** JSON editor (lazy-loaded) for the variable editor's source mode.
- **Degrade, don't blank** — partial, labeled results when an engine is unreachable; a status
  derived from truncated data is always badged, never shown as if complete.
- Built with React 18 · TypeScript `strict` · Vite. All calls go through a single generated
  `openapi-fetch` client, so the bundle is origin-relative and needs no build-time API URL.

## Run it

This image expects the BFF reachable as the service name **`backend`** on the same network —
`nginx.conf` proxies `/api` there. Don't run it standalone; use the published quick-start
compose, which wires the SPA, the BFF, and Postgres together:

```bash
curl -LO https://github.com/x3kcl/process-inspector/releases/latest/download/docker-compose.release.yml
INSPECTOR_DEV_PASSWORD=pick-one docker compose -f docker-compose.release.yml up -d
# UI on http://localhost:8080
```

### Ports

- `80` — HTTP (nginx). Map it to a host port (the compose bundle publishes `8080:80`).

### Notes

- The API tier is resolved by the service name `backend` — keep that name, or edit
  `nginx.conf` and rebuild.
- One origin, one session: the SPA and `/api` are served from the same host, so there is no
  CORS and cookie CSRF works out of the box.

## Tags

- `latest`, `X.Y.Z`, `X.Y` — versioned releases (semver; `latest` tracks the newest stable).
- `edge` — the tip of `main` after a green CI run.
- `sha-<short>` — the exact commit of an edge build, for pinning.

The same tags are mirrored to `ghcr.io/x3kcl/process-inspector-web`.

## License

Apache-2.0. The SPA embeds bpmn-js under the bpmn.io license — the bpmn.io watermark must not
be removed.
