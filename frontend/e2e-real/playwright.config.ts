// Real-stack smoke (issue #466, TEST-STRATEGY §12): Playwright → Vite → BFF → dockerized
// Flowable engines. The opposite of ../playwright.config.ts — NO page.route anywhere; every
// /api call reaches a running BFF (INSPECTOR_BFF_URL), which reaches real engines. Runs in
// the nightly `e2e-real` job, never per PR (it needs the compose harness and a booted jar).
//
// Ports are slot-aware like the hermetic config: PI_E2E_PORT for Vite, PI_ENGINE_*_PORT for
// the fixture seeding in global-setup.ts (docker/ci-runner slot env, resolved via
// scripts/free-port.sh by the workflow).
import { defineConfig } from '@playwright/test'

const PORT = process.env.PI_E2E_PORT ?? '5173'
const BFF = process.env.INSPECTOR_BFF_URL ?? 'http://localhost:8085'

export default defineConfig({
  testDir: '.',
  globalSetup: './global-setup.ts',
  // The arcs share one engine pair and mutate it — serial, in file order, no retries: a
  // re-run of a mutating arc would act on already-changed state and mask the first failure.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  // Real engines: the async executor's acquisition cycle sits inside every mutate→verify step.
  timeout: 180_000,
  expect: { timeout: 15_000 },
  outputDir: '../test-results/real',
  reporter: [['html', { open: 'never', outputFolder: '../playwright-report/real' }], ['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    env: { INSPECTOR_BFF_URL: BFF },
  },
})
