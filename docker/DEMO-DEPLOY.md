# Demo deployment — pi.naumann.cloud (hp04 Traefik)

Self-contained demo stack: two Flowable 6.8 engines + Postgres + the BFF + an nginx that
serves the SPA and reverse-proxies `/api` to the BFF, published as one HTTPS origin behind
the existing hp04 Traefik.

```
Traefik ──(docker_discovery, TLS)──▶ frontend (nginx: SPA + /api proxy) ──▶ backend (BFF) ──▶ engine-a / engine-b (6.8.0)
                                                                            ├──▶ engine-7 (7.1.0)
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
# from the DURABLE repo root, on the hp04 host (see the next paragraph — NOT from a
# .claude/worktrees/* checkout) and with the external `docker_discovery` net already created
docker/deploy-demo.sh          # defaults to the latest :edge (post-merge-to-main) build
docker/deploy-demo.sh v0.3.0   # or pin a specific versioned release tag instead
git push origin HEAD --tags    # publish the attribution commit + demo-YYYY-MM-DD-<sha> tag
```

### Deploy only from a durable checkout (issue #396)

**Where you run the deploy from is part of what it deploys.** The compose file mounts the
backup machinery by *relative* path (`./backup/audit-backup:/scripts:ro`,
`./backup/postgres/pg_hba-demo.conf:…`), and compose resolves those against the compose
file's own directory — so the checkout you deploy from is frozen into the live containers as
their bind-mount source, for as long as those containers live. `deploy-demo.sh` prints the
resolved path on every run:

```
Compose bind-mount root (frozen into the containers): /home/flapci/workspace/pi-wt-selfheal/docker
```

If that path can be deleted, the deploy is a latent outage. The 2026-08-08 deploy ran from
`.claude/worktrees/cursor-demo-refresh-f543/`, which is auto-cleaned; when it went away
`audit-backup` and `audit-basebackup` failed every cron run (**11 days with no logical dump**,
no basebackup for 16), and `postgres` became a restart-bomb — Docker recreates a missing
bind source as an empty root-owned *directory*, so `hba_file` points at a directory and the
database refuses to start on its next restart.

The script now **refuses** to run when its own root matches `*/.claude/worktrees/*`, printing
the durable checkout to use instead. Unlike the #377 and #370 guards below there is
deliberately **no override flag**: an override would freeze the mounts anyway, which is the
entire harm, and re-running from the durable checkout is always available and always correct.

Recovering a stack that was already deployed from a deleted path (re-point the mounts; the
`postgres` step is a brief, deliberate demo-DB outage — data lives on a named volume):

```bash
cd <durable-checkout>/docker
docker compose -p process-inspector-demo -f docker-compose.demo.yml --env-file .env.demo \
  up -d --no-deps --force-recreate audit-backup audit-basebackup wal-receiver
docker compose -p process-inspector-demo -f docker-compose.demo.yml --env-file .env.demo \
  up -d --no-deps postgres
docker exec process-inspector-demo-audit-backup-1 /scripts/backup-once.sh   # catch up the gap
```

Sign in with the ladder users `viewer` / `responder` / `operator` / `admin`
(password = `INSPECTOR_DEV_PASSWORD`, default `dev`). Override host/creds in
`docker/.env.demo`.

## Topology changes are NOT applied by these scripts (issue #370)

`deploy-demo.sh` and `rollback-demo.sh` update **digest-pinned images**. Every `docker compose
up` they issue is **service-scoped**, so a service added to or removed from
`docker-compose.demo.yml` is *not* created or destroyed by running them.

That scoping is deliberate — an unscoped `up -d` would re-run the one-shot `seed` service
(whose instance starts are **not** idempotent, see `docker/seed.sh`'s header) and can recreate
the `internal` network. On 2026-08-05 exactly that dropped the engines' compose DNS aliases,
and the `--force-recreate` used to repair it destroyed 16 days of pilot ledger history.

It used to be **silent**, which is what #370 fixed. When #368 added the `engine-7` service the
deploy printed success, passed the `/api/engines → 401` chain check, committed `.env.demo` and
tagged the release — while the container was never created. Both scripts now **refuse before
touching `.env.demo`** if the compose service list and `docker compose ps --services --all`
disagree, naming what is missing or extra:

```
REFUSING: the running topology does not match docker/docker-compose.demo.yml.
  declared but NOT created: engine-7
```

Converge topology deliberately — prefer naming the specific services
(`docker compose -f docker/docker-compose.demo.yml --env-file docker/.env.demo up -d <svc>`)
and re-check `ps --services --all` afterwards — then re-run the deploy. `--allow-topology-drift`
overrides the refusal; use it only when you have reconciled by hand and accept that the tag the
script writes then claims a state it did not verify.

## Rollback

`docker/rollback-demo.sh <demo-tag>` restores a PRIOR deploy's exact pinned digest pair from
git history (no re-resolution — safe against a floating tag having since moved) and
redeploys. `docker/rollback-demo.sh --list` shows recent demo deploy tags. See RUNBOOK.md §8
for the drilled procedure and when to reach for this vs. `deploy-demo.sh`.

## Engine state (issue #377) — READ THIS before recreating an engine

`engine-a`/`engine-b` (6.8.0) and `engine-7` (7.1.0) each keep their process/job/history data
in `flowable-rest`'s embedded H2 store, which now lives on a **named volume per engine**
(`inspector-demo-engine-a-home` / `-b-home` / `-7-home`, mounted at each container's
`/home/flowable`) instead of the container's own throwaway filesystem — see the per-service
comments in `docker/docker-compose.demo.yml` for the exact H2 paths verified against both
images and how the volume-ownership fix was proven.

This closes the gap that destroyed 16 days of pilot state on 2026-08-05: a
`--force-recreate` against the engines (at the time, run to repair DNS aliases — nothing to
do with engine data) silently wiped everything, and the demo *looked* fine afterwards
because the seed container quietly re-ran a fresh minimal set. With the volume in place, a
container recreate (image bump, env change, alias repair, `--force-recreate`) now survives —
**but two things still destroy engine state**:

- **`docker compose ... down -v`** removes named volumes along with containers/networks —
  now including the three engine volumes above, not just `inspector-demo-pgdata` and the
  backup volumes. `down -v` was already destructive to the BFF's own store; it is now ALSO
  destructive to every engine's data. Never run it against this stack without a deliberate
  decision to lose both.
- **Detaching or deleting a volume directly** (`docker volume rm inspector-demo-engine-a-home`,
  or recreating the stack under a different compose `name:`/project) — the volume, not the
  container, is what's durable.

`docker/deploy-demo.sh` and `docker/rollback-demo.sh` never target the engines (their `up`
calls are scoped to `backend`/`frontend` and the backup sidecars) and both now hard-refuse
any future call that WOULD recreate an engine — or that omits a service list, which
`docker compose up` treats as "every service" — unless `--allow-engine-recreate` is passed
explicitly:

```bash
docker/deploy-demo.sh --allow-engine-recreate edge
docker/rollback-demo.sh --allow-engine-recreate demo-2026-07-12-a1b2c3d
```

A **manual** `docker compose -f docker/docker-compose.demo.yml up -d --force-recreate
engine-a` outside either script is not gated by this — it never was, and can't be from
inside a shell script someone isn't running. That's exactly what caused the incident. The
volume is the actual fix; the script guard and this section are the "make the trap visible"
half.

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
