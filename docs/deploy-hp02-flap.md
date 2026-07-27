# Deploying the Inspector beside production flap (hp02)

> Want to SEE it first, without touching production? `docker/docker-compose.flapdemo.yml`
> stands the same two-tier app up against the `flap` harness engine on any box —
> `docker compose -f docker/docker-compose.dev.yml --profile flap up -d && bash docker/seed.sh`
> for the engine and its data, then that file for the inspector. The topology below is the
> production shape of it.

The `cloud` user's stack on hp02 (`/home/cloud/docker/docker-compose.yml`, compose project
`docker`) already runs production flap as service **`flap`** (container `flowable`, published on
host `:8082`). This adds the Process Inspector to that same file, with the engine channel confined
to a private, internet-less docker network.

## Topology

```
host :9080 ──▶ pi-web (nginx: SPA + /api proxy)
                  │  pi-net
                  ▼
               pi-bff (BFF, alias `backend`) ──▶ pi-db (Postgres: audit / notes / bulk)
                  │
                  │  pi-engine   ← internal: true (no gateway, unroutable from outside the host)
                  ▼
               flap  (:8080 in-network, /process-api)
```

Only `pi-web` publishes a port. The BFF↔engine channel is its own network that nothing else joins,
and `internal: true` means it has no gateway at all — flap keeps its internet access through the
stack's default network, and the inspector's engine traffic cannot leave the host by that path.
Plain HTTP is the correct choice inside it: there is no TLS to strip on a link with no route off
the box, and the alternative (a cert for an in-network name) buys nothing.

## 1. Add to `/home/cloud/docker/docker-compose.yml`

```yaml
  # ── Process Inspector — management plane for the flap Flowable engine ──────
  pi-db:
    image: postgres:16-alpine
    environment:
      - POSTGRES_DB=inspector
      - POSTGRES_USER=inspector
      - POSTGRES_PASSWORD=${PI_DB_PASSWORD:?set PI_DB_PASSWORD in .env}
      - TZ=Europe/Berlin
    volumes:
      - pi-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U inspector -d inspector"]
      interval: 5s
      timeout: 3s
      retries: 24
    networks: [pi-net]
    restart: unless-stopped

  pi-bff:
    image: ghcr.io/x3kcl/process-inspector-bff:edge
    environment:
      - SERVER_PORT=8080
      - TZ=Europe/Berlin
      - INSPECTOR_DB_URL=jdbc:postgresql://pi-db:5432/inspector
      - INSPECTOR_DB_USER=inspector
      - INSPECTOR_DB_PASSWORD=${PI_DB_PASSWORD:?set PI_DB_PASSWORD in .env}
      # Sign-in for the UI ladder users viewer/responder/operator/admin.
      - INSPECTOR_DEV_PASSWORD=${PI_UI_PASSWORD:?set PI_UI_PASSWORD in .env}
      # The engine credential, resolved by NAME from the registry's password-ref. Same
      # variable flap's service account is created from — one secret, one .env line.
      - FLAP_ENGINE_PASSWORD=${PROCESS_API_CLIENT_PASSWORD:?set PROCESS_API_CLIENT_PASSWORD in .env}
    volumes:
      - ./pi/application.yml:/app/config/application.yml:ro
    depends_on:
      pi-db:
        condition: service_healthy
    networks:
      pi-net:
        aliases:
          - backend        # pi-web's nginx proxies /api to the literal http://backend:8080
      pi-engine: {}        # the private channel to flap
    restart: unless-stopped

  pi-web:
    image: ghcr.io/x3kcl/process-inspector-web:edge
    ports:
      - "9080:80"
    depends_on: [pi-bff]
    networks: [pi-net]
    restart: unless-stopped
```

Top-level, alongside the file's existing `volumes:`:

```yaml
networks:
  pi-net:
  pi-engine:
    # No gateway: containers on this network can reach each other and nothing else.
    internal: true

volumes:
  pi-pgdata:
```

## 2. Two additions to the existing `flap` service

```yaml
  flap:
    # …everything already there…
    environment:
      # …existing…
      - PROCESS_API_CLIENT_USER=inspector
      - PROCESS_API_CLIENT_PASSWORD=${PROCESS_API_CLIENT_PASSWORD:?}
    networks:
      default: {}        # ⚠ SEE BELOW
      pi-engine: {}
```

> **⚠ The one footgun.** A service with no `networks:` key is implicitly on `default`. The moment
> you add the key, that implicit membership is gone — list `default` explicitly or flap loses its
> route to `flapdb`, `stash`, `sabnzbd` and `ollama` and will not start. Adding a network also
> requires a container **recreate**, i.e. a flap restart; plan it like any other flap deploy.

`.env` gains three lines:

```
PI_DB_PASSWORD=…
PI_UI_PASSWORD=…
PROCESS_API_CLIENT_PASSWORD=…
```

## 3. `/home/cloud/docker/pi/application.yml`

The published image ships a two-engine demo registry. Spring reads `/app/config/application.yml`
(WORKDIR is `/app`) at higher precedence than the classpath one, and a list binds wholly from the
winning source — so this file REPLACES the demo engines rather than adding to them.

```yaml
inspector:
  registry:
    # Postgres is authoritative after the first boot; this list is the one-time seed, and
    # later edits go through the admin UI. Uncomment to keep this file the source of truth
    # instead — that disables in-app registry CRUD:
    #   source: config
    egress-allowlist:
      - flap
      - 172.16.0.0/12
  engines:
    - id: flap-prod
      name: "flap (production · hp02)"
      # Boot layout: an embedded engine serves /process-api, and the inspector derives
      # /cmmn-api + /external-job-api as its ROOT-level siblings. Not /flowable-rest/service.
      base-url: "http://flap:8080/process-api"
      environment: prod
      accent-color: "#c0392b"
      enabled: true
      # Start on the read-only rung; flip to read-write once you trust the join.
      mode: read-only
      auth:
        type: basic
        username: inspector          # flap's dedicated service account, NOT a human login
        password-ref: FLAP_ENGINE_PASSWORD
      timeouts:
        connect-ms: 2000
        # An embedded engine shares its JVM with a whole application; it is not the war
        # image's single-purpose request loop, so the read budget is the generous end.
        read-ms: 15000
        write-ms: 15000
      max-page-size: 100
      dlq-scan-cap: 5000
      # flap's scout/acquire jobs legitimately hold a job for minutes — the stock 5/15 min
      # starvation alarms would sit red permanently.
      alarm-thresholds:
        oldest-job-warn-min: 10
        oldest-job-crit-min: 30
        overdue-timer-grace-s: 120
```

Two consequences of `environment: prod`, both deliberate:

- It arms the strict guard ladder (ticket policy, ADMIN floor for flow surgery, tier-1
  confirmations) — right for production flap.
- The SSRF rails reject `http://` for a prod engine, so this row is never *pinned* and cannot be
  edited or re-probed from the admin Engines page (400 "prod engines must use https"). YAML-seeded
  rows skip that validation, so traffic works normally. If in-app CRUD on this row matters more,
  use `environment: dev` — then the `flap` glob + `172.16.0.0/12` CIDR above are what let a private
  docker address past the denylist, at the cost of the prod guard rails.

## 4. Deploy

```bash
mkdir -p /home/cloud/docker/pi        # then drop application.yml in
cd /home/cloud/docker
docker compose up -d flap             # recreates flap: new network + service account
docker compose up -d pi-db pi-bff pi-web

# flap's service account answers over Basic (401 = wrong password, 302 = chain not matched):
docker compose exec pi-bff sh -c 'wget -qO- --user=inspector --password=$FLAP_ENGINE_PASSWORD \
  http://flap:8080/process-api/management/engine'
# whole chain healthy (401 is the expected auth-required answer from the BFF):
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9080/api/engines
```

UI on `http://hp02:9080`, sign in as `admin` / `$PI_UI_PASSWORD`. The default auth chain is the dev
ladder (viewer/responder/operator/admin); an `oidc` profile exists if a real IdP is ever wanted.

## Version note

The Boot-layout sibling derivation (`/cmmn-api`, `/external-job-api` at the root) landed after
`v0.9.1`, so the images above are pinned to `:edge`. Once a release carries it, pin the version
tag instead — `:edge` moves on every green `main`, which is not what a production stack wants
long-term. On `v0.9.1` the process-api lanes work; only the Case Inspector and external-worker
drill-downs would 404.

## Why the host port is 9080

8080/8081/8082 on hp02 are UniFi / sabnzbd / flap, and the Process Inspector CI runner slots
reserve `8x86`, `8x91–8x95`, `5xx5` and `4x73`. 9080 is clear of all of it.
