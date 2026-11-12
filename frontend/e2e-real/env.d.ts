// The real-stack smoke reads its slot ports and run prefix from the environment. @types/node is
// not a dependency of this SPA (the browser code must never see `process`), so declare only the
// one shape these Node-side files use.
declare const process: { env: Record<string, string | undefined> }
