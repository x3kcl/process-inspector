// Minimal ambient typing for the #245 stylesheet guardrail test (DepthWallNote.test.tsx),
// which reads styles.css as text at test time. This repo deliberately has no @types/node
// (src is DOM-typed; vitest stubs CSS imports — even `?raw` — to '', so a Vite raw import
// cannot serve here). Only the one shape the test uses is declared; if @types/node is ever
// added, delete this file.
declare module 'node:fs' {
  export function readFileSync(path: URL | string, encoding: 'utf8'): string
}
