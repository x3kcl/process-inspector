// UI smoke tests (v1.1): hermetic by construction — every /api call is intercepted with
// page.route in the specs, so no BFF or engine needs to run. The web server is the plain
// Vite dev server; its /api proxy is never reached because routes fulfill first.
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: 'e2e',
  timeout: 30_000,
  use: {
    baseURL: 'http://localhost:4173',
  },
  webServer: {
    command: 'npm run dev -- --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
  },
})
