// UI smoke tests (v1.1): hermetic by construction — every /api call is intercepted with
// page.route in the specs, so no BFF or engine needs to run. The web server is the plain
// Vite dev server; its /api proxy is never reached because routes fulfill first.
//
// Port is slot-aware (PI_E2E_PORT, docker/ci-runner/docker-compose.yml): CI runner slots share
// the HOST network (network_mode: host), so two concurrent e2e jobs on different slots binding
// the same hardcoded port would collide — each slot carries its own disjoint port, with 4173
// (slot-1's value) as the local-dev fallback.
import { defineConfig } from '@playwright/test'

const PORT = process.env.PI_E2E_PORT ?? '4173'

export default defineConfig({
  testDir: 'e2e',
  timeout: 30_000,
  // HTML report (traces/screenshots on failure) so the CI job's failure-artifact upload has
  // something to grab; 'never' skips auto-opening a browser tab in CI.
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
  },
})
