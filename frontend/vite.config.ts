/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// All /api calls go to the BFF — the browser never talks to a Flowable engine.
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        // U3 (#88): split the heavy vendors into their own long-cacheable chunks so they never
        // bloat (or invalidate) the app entry. bpmn-js / cmmn-js load only with their lazy pages;
        // ag-grid rides Search but out of the entry; react is a stable vendor chunk.
        manualChunks(id: string): string | undefined {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('bpmn-js') || id.includes('bpmn-moddle')) return 'vendor-bpmn'
          if (id.includes('cmmn-js') || id.includes('cmmn-moddle')) return 'vendor-cmmn'
          if (id.includes('diagram-js')) return 'vendor-diagram'
          if (id.includes('ag-grid')) return 'vendor-ag-grid'
          if (id.includes('@codemirror') || id.includes('codemirror')) return 'vendor-codemirror'
          if (id.includes('/react/') || id.includes('/react-dom/') || id.includes('/scheduler/'))
            return 'vendor-react'
          return undefined
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.INSPECTOR_BFF_URL ?? 'http://localhost:8085',
    },
  },
  test: {
    // Default stays node (pure-logic tests); component tests opt into jsdom per-file
    // via the `// @vitest-environment jsdom` pragma.
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
    // Test-support consolidation (Q8, issue #90): MEASURE coverage on demand (`npm run
    // test:coverage`), report-only — not wired into the default `npm test`/CI `frontend`
    // job, and no threshold here. TEST-STRATEGY.md's "≥70% frontend logic" floor was
    // previously an unfalsifiable claim (no tool anywhere measured it); this makes the
    // number real and visible before any gate is added on top of it.
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/api/schema.d.ts'],
    },
  },
})
