// cmmn-js (0.20.x) predates bundled TypeScript types (unlike bpmn-js, which ships .d.ts).
// This is the minimal surface the read-only Case Inspector canvas uses — a NavigatedViewer
// with the same diagram-js core services (Canvas, Overlays) bpmn-js exposes. The bpmn.io
// "Powered by" watermark the viewer injects is a license term (R-GOV-05) and is never touched —
// enforced by scripts/check-bpmn-watermark.mjs.
declare module 'cmmn-js/lib/NavigatedViewer' {
  export interface CmmnViewerOptions {
    container?: HTMLElement | string
  }
  export default class NavigatedViewer {
    constructor(options?: CmmnViewerOptions)
    // cmmn-js 0.20 predates bpmn-js's Promise-based importXML — it uses the CALLBACK form
    // (`done(err, warnings)`), so it must NOT be awaited (returns undefined, not a thenable).
    importXML(xml: string, done: (err: Error | null, warnings?: string[]) => void): void
    // The generic mirrors bpmn-js's own NavigatedViewer typing so `get<Canvas>('canvas')` reads
    // the same across both viewers; it is intentional despite T appearing once.
    // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters
    get<T = unknown>(name: string): T
    on(event: string, callback: (event: unknown) => void): void
    destroy(): void
  }
}
