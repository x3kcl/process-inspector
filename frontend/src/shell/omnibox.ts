// The omnibox accepts "a paste of anything" (SPEC §4). Full R-SEM-04 resolution (raw
// process/execution/task/job IDs across engines) belongs to GET /api/resolve, which the
// BFF does not expose yet. Until it lands, exactly two paths are honestly supportable
// client-side and both are implemented here:
//   1. composite "engine:id" (the inspector's own ID format) → straight to Stage 2;
//   2. anything else → a pre-filtered business-key search (which is what R-SEM-04
//      prescribes for business keys anyway — never an auto-navigate).
export type OmniboxTarget =
  | { kind: 'inspect'; engineId: string; instanceId: string }
  | { kind: 'business-key'; businessKey: string }

export function classifyOmniboxInput(
  raw: string,
  knownEngineIds: readonly string[],
): OmniboxTarget | null {
  const input = raw.trim()
  if (input === '') return null
  const at = input.indexOf(':')
  if (at > 0) {
    const prefix = input.slice(0, at)
    const rest = input.slice(at + 1).trim()
    // Only a registered engine id makes the prefix a composite ID — "order:4711" where
    // "order" is no engine stays a business key.
    if (rest !== '' && knownEngineIds.includes(prefix)) {
      return { kind: 'inspect', engineId: prefix, instanceId: rest }
    }
  }
  return { kind: 'business-key', businessKey: input }
}
