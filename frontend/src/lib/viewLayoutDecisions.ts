// #197 (docs/SHARED-VIEWS.md §8): remembers whether the operator already answered a
// saved/shared view's column-layout suggestion, so the confirm prompt is asked once per
// (search-identity, suggested-layout) pair, never on every visit. Deliberately NOT reactive
// (no useSyncExternalStore) — callers read once per relevant param change and re-render via
// their own local state on the SAME click that writes a decision.
const STORAGE_KEY = 'inspector.viewLayoutDecisions'

// A small cap, not a hard limit on correctness: the worst case of eviction is re-asking a
// prompt for a view opened long ago, never a wrong or stale answer for a recent one.
const MAX_ENTRIES = 50

export type LayoutDecision = 'applied' | 'dismissed'

function readAll(): Map<string, LayoutDecision> {
  try {
    const raw = globalThis.localStorage.getItem(STORAGE_KEY)
    if (raw === null) return new Map()
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return new Map()
    const entries = parsed.filter(
      (entry): entry is [string, LayoutDecision] =>
        Array.isArray(entry) &&
        entry.length === 2 &&
        typeof entry[0] === 'string' &&
        (entry[1] === 'applied' || entry[1] === 'dismissed'),
    )
    return new Map(entries)
  } catch {
    return new Map()
  }
}

function writeAll(decisions: Map<string, LayoutDecision>): void {
  try {
    globalThis.localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(decisions.entries())))
  } catch {
    // Persistence unavailable — the decision still holds for the rest of this session via
    // the caller's own component state, it just won't be remembered on the next visit.
  }
}

/** The key identifying "this view + this suggestion" — search identity, cols-excluded, plus the suggested set. */
export function decisionKey(searchWithoutCols: string, suggestedCols: ReadonlySet<string>): string {
  return `${searchWithoutCols}::${Array.from(suggestedCols).sort().join(',')}`
}

export function getLayoutDecision(key: string): LayoutDecision | undefined {
  return readAll().get(key)
}

export function recordLayoutDecision(key: string, decision: LayoutDecision): void {
  const decisions = readAll()
  // Re-inserting an existing key moves it to the end (Map preserves insertion order) — an
  // active view's decision is never the one evicted while it keeps being revisited.
  decisions.delete(key)
  decisions.set(key, decision)
  while (decisions.size > MAX_ENTRIES) {
    const oldest = decisions.keys().next().value
    if (oldest === undefined) break
    decisions.delete(oldest)
  }
  writeAll(decisions)
}
