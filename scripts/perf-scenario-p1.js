/**
 * P1 performance scenario — the SPEC §2 (R-NFR-01) latency SLAs, asserted as k6 thresholds
 * so the run itself is the release gate (SPEC §13: "performance budget green in CI",
 * R-NFR-02; wired as the nightly release-blocking perf job, OPERATIONS §8).
 *
 * Gates asserted (normative numbers from SPEC §2 "Quantified service levels"):
 *   - Triage landing, warm cache ..... P95 <  500 ms   (GET  /api/triage)
 *   - Search, mixed criteria ......... P95 < 3000 ms   (POST /api/search)
 *   - Search, FAILED-only ............ P95 < 5000 ms   (POST /api/search, DLQ-driven plan)
 *
 * The bounds are defined against the REFERENCE DATASET (5 engines / 5k DLQ envelope,
 * TEST-STRATEGY). Numbers measured against an empty dev stack still pass but prove little —
 * CI must run this after `docker/seed.sh` has populated the engine matrix.
 *
 * Triage "warm" semantics: /api/triage sits behind a ~20 s single-flight BFF cache
 * (SPEC §4 Stage 0). setup() primes it once (cold, unasserted); at the test arrival rate,
 * cache refills are a per-20s single flight, so >99% of measured requests are warm and the
 * P95 threshold is a faithful warm-SLA assertion.
 *
 * Usage:
 *   k6 run scripts/perf-scenario-p1.js
 *   BASE_URL=http://localhost:8080 INSPECTOR_USER=viewer INSPECTOR_PASSWORD=dev \
 *     k6 run scripts/perf-scenario-p1.js
 *
 * Auth: dev-ladder Basic (SecurityConfig, non-oidc profiles). VIEWER suffices — both
 * endpoints are read-only. Against an oidc deployment, front the run with a token and
 * set AUTH_HEADER instead.
 */
import http from 'k6/http';
import { check, fail } from 'k6';
import encoding from 'k6/encoding';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_HEADER =
  __ENV.AUTH_HEADER ||
  'Basic ' +
    encoding.b64encode(
      `${__ENV.INSPECTOR_USER || 'viewer'}:${__ENV.INSPECTOR_PASSWORD || 'dev'}`,
    );

const PARAMS = {
  headers: { Authorization: AUTH_HEADER, 'Content-Type': 'application/json' },
  timeout: '30s',
};

export const options = {
  // Arrival-rate executors so a slow BFF cannot dodge the SLA by throttling the loop
  // (open model: late responses pile up instead of slowing the request rate).
  scenarios: {
    triage: {
      executor: 'constant-arrival-rate',
      exec: 'triage',
      rate: 5,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 10,
      maxVUs: 40,
      tags: { scenario: 'triage' },
    },
    search: {
      executor: 'constant-arrival-rate',
      exec: 'search',
      rate: 2,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 10,
      maxVUs: 40,
      tags: { scenario: 'search' },
    },
    search_failed: {
      executor: 'constant-arrival-rate',
      exec: 'searchFailedOnly',
      rate: 1,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 5,
      maxVUs: 20,
      tags: { scenario: 'search_failed' },
    },
  },
  thresholds: {
    // ---- the SPEC §2 gates ----
    'http_req_duration{scenario:triage}': ['p(95)<500'],
    'http_req_duration{scenario:search}': ['p(95)<3000'],
    'http_req_duration{scenario:search_failed}': ['p(95)<5000'],
    // A fast 500 must not pass the latency gate: correctness thresholds per scenario.
    'checks{scenario:triage}': ['rate>0.99'],
    'checks{scenario:search}': ['rate>0.99'],
    'checks{scenario:search_failed}': ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
};

/** Prime auth + the triage cache once; the cold fill is deliberately not asserted. */
export function setup() {
  const warm = http.get(`${BASE_URL}/api/triage`, {
    ...PARAMS,
    tags: { scenario: 'setup' },
  });
  if (warm.status !== 200) {
    fail(
      `setup: GET /api/triage returned ${warm.status} — check BASE_URL/credentials ` +
        `and that the BFF is up (engines may be degraded; the endpoint still serves 200).`,
    );
  }
}

export function triage() {
  const res = http.get(`${BASE_URL}/api/triage`, PARAMS);
  check(res, {
    'triage 200': (r) => r.status === 200,
    // Envelope honesty: the aggregation payload, not an error page.
    'triage has engine aggregations': (r) => {
      const body = r.json();
      return body !== null && typeof body === 'object' && 'engines' in body;
    },
  });
}

// Mixed-criteria search: native pushdown (status + start window) exercising the
// grid-search plan across all enabled engines. 24h window keeps the reference-dataset
// result bounded the way a real incident search is.
export function search() {
  const body = JSON.stringify({
    statuses: ['ACTIVE', 'FAILED'],
    startedAfter: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
    sortBy: 'startTime',
    pageSize: 50,
  });
  const res = http.post(`${BASE_URL}/api/search`, body, PARAMS);
  checkSearch(res);
}

// FAILED-only forces the inverted DLQ-driven plan (ARCH §2.3) — the expensive path with
// its own, looser bound (≤5s at 5k DLQ, R-NFR-01).
export function searchFailedOnly() {
  const body = JSON.stringify({
    statuses: ['FAILED'],
    sortBy: 'failureTime',
    pageSize: 50,
  });
  const res = http.post(`${BASE_URL}/api/search`, body, PARAMS);
  checkSearch(res);
}

function checkSearch(res) {
  check(res, {
    'search 200': (r) => r.status === 200,
    'search has rows + per-engine envelope': (r) => {
      const body = r.json();
      return (
        body !== null &&
        typeof body === 'object' &&
        Array.isArray(body.rows) &&
        'perEngine' in body
      );
    },
  });
}
