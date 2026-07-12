# Demo deployment — pi.naumann.cloud (hp04 Traefik)

Self-contained demo stack: two Flowable 6.8 engines + Postgres + the BFF + an nginx that
serves the SPA and reverse-proxies `/api` to the BFF, published as one HTTPS origin behind
the existing hp04 Traefik.

```
Traefik ──(docker_discovery, TLS)──▶ frontend (nginx: SPA + /api proxy) ──▶ backend (BFF) ──▶ engine-a / engine-b
                                                                            └──▶ postgres
seed (one-shot): deploys the demo BPMN + starts one instance per status arc, then exits.
```

Only `frontend` is exposed; the BFF, engines and DB have no host ports and never touch the
proxy network. That proxy network is the hp04 Traefik net, whose real Docker name is
**`docker_discovery`** (the compose alias `discovery` maps to it via `name:`). Because the
frontend sits on two networks, the `traefik.docker.network=docker_discovery` label is
required so Traefik reaches nginx on the right IP.

## Deploy / update

`backend`/`frontend` are pinned by **digest** (issue #92), not a floating tag and not a
local build — `docker/deploy-demo.sh` resolves the digest for a published tag, writes it
into `docker/.env.demo`, redeploys, verifies, and commits+tags the result so what's running
is always attributable to one exact build (`git log docker/.env.demo`).

```bash
# from the repo root, on the hp04 host (the external `docker_discovery` net must already exist)
docker/deploy-demo.sh          # defaults to the latest :edge (post-merge-to-main) build
docker/deploy-demo.sh v0.3.0   # or pin a specific versioned release tag instead
git push origin HEAD --tags    # publish the attribution commit + demo-YYYY-MM-DD-<sha> tag
```

Sign in with the ladder users `viewer` / `responder` / `operator` / `admin`
(password = `INSPECTOR_DEV_PASSWORD`, default `dev`). Override host/creds in
`docker/.env.demo`.

## Rollback

`docker/rollback-demo.sh <demo-tag>` restores a PRIOR deploy's exact pinned digest pair from
git history (no re-resolution — safe against a floating tag having since moved) and
redeploys. `docker/rollback-demo.sh --list` shows recent demo deploy tags. See RUNBOOK.md §8
for the drilled procedure and when to reach for this vs. `deploy-demo.sh`.

## TLS / HSTS — READ THIS if the browser blocks the site

The demo router requests a Let's Encrypt cert via the `mytlschallenge` resolver. Until that
cert is actually issued and trusted, Traefik serves its self-signed default and the browser
shows a cert warning.

HSTS here is **moderate** (S5) — `stsSeconds=86400` (24 h), **no** `stsPreload`, **no**
`stsIncludeSubdomains`. 24 h actually resists an SSL-strip (the former 5 min barely did),
while a transient cert incident self-heals within a DAY rather than the up-to-a-year lockout a
long, preloaded, subdomain-spanning entry would cause (preload can affect the whole
`naumann.cloud` domain). Raise `stsSeconds` further — and only then consider preload /
includeSubdomains — after a long soak on a stable cert.

If a browser is already locked out from an earlier aggressive-HSTS attempt: it must clear
its stored HSTS state (Firefox: History ▸ Forget About This Site → `pi.naumann.cloud`;
Chrome: `chrome://net-internals/#hsts` → Delete domain). Softening the header alone will not
release an existing entry until a *trusted* HTTPS response with the smaller max-age arrives.

## Troubleshooting a 502 / 504

Probe the chain (401 = healthy; the endpoint requires auth):

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://pi.naumann.cloud/api/engines
#  401 → whole chain healthy (Traefik→nginx→BFF all good)
#  504 → Traefik reached nginx but the BFF is down/slow  → check `backend`
#  502 → nginx could not reach the BFF                   → check the `internal` network
#  404/cert error → Traefik router or cert not ready     → check the router + resolver
```

```bash
docker compose -f docker/docker-compose.demo.yml ps         # backend Up (not Restarting); seed Exited(0)
docker compose -f docker/docker-compose.demo.yml logs backend --tail=40   # binding/DB errors?
docker compose -f docker/docker-compose.demo.yml exec frontend \
  wget -qO- http://backend:8080/api/engines                 # 401 body = nginx→BFF path OK

# Traefik must share the proxy network with the frontend container:
docker network inspect docker_discovery -f '{{range .Containers}}{{.Name}} {{end}}'
#  → must list BOTH the traefik container AND process-inspector-demo-frontend-1
```

The static SPA (`/`) is served from disk, so it stays 200 even when the BFF is down — a 504
on `/` itself points at Traefik↔frontend (network/port), while a page that loads but whose
data times out points at the BFF (`backend`).
