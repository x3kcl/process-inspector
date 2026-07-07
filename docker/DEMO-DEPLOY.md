# Demo deployment — pi.naumann.cloud (hp04 Traefik)

Self-contained demo stack: two Flowable 6.8 engines + Postgres + the BFF + an nginx that
serves the SPA and reverse-proxies `/api` to the BFF, published as one HTTPS origin behind
the existing hp04 Traefik.

```
Traefik ──(discovery net, TLS)──▶ frontend (nginx: SPA + /api proxy) ──▶ backend (BFF) ──▶ engine-a / engine-b
                                                                            └──▶ postgres
seed (one-shot): deploys the demo BPMN + starts one instance per status arc, then exits.
```

Only `frontend` is exposed; the BFF, engines and DB have no host ports and never touch the
`discovery` network.

## Deploy / update

```bash
# from the repo root, on the hp04 host (the external `discovery` network must already exist)
docker compose -f docker/docker-compose.demo.yml --env-file docker/.env.demo up -d --build
```

Sign in with the ladder users `viewer` / `responder` / `operator` / `admin`
(password = `INSPECTOR_DEV_PASSWORD`, default `dev`). Override host/creds in
`docker/.env.demo`.

## TLS / HSTS — READ THIS if the browser blocks the site

The demo router requests a Let's Encrypt cert via the `mytlschallenge` resolver. Until that
cert is actually issued and trusted, Traefik serves its self-signed default and the browser
shows a cert warning.

HSTS here is **deliberately weak** — `stsSeconds=300`, **no** `stsPreload`, **no**
`stsIncludeSubdomains`. A long, preloaded, subdomain-spanning HSTS entry turns a transient
cert problem into an unbypassable, up-to-a-year lockout (and preload can affect the whole
`naumann.cloud` domain). Only raise `stsSeconds` once the cert is stable.

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

# Traefik must share the discovery network with the frontend container:
docker network inspect discovery -f '{{range .Containers}}{{.Name}} {{end}}'
#  → must list BOTH the traefik container AND process-inspector-demo-frontend-1
```

The static SPA (`/`) is served from disk, so it stays 200 even when the BFF is down — a 504
on `/` itself points at Traefik↔frontend (network/port), while a page that loads but whose
data times out points at the BFF (`backend`).
