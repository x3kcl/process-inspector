import { PendingContract } from '../PendingContract'

/** Call-activity tree, both directions, depth-capped at 10 + breadth-capped 50 (R-SEM-19). */
export default function HierarchyTab() {
  return (
    <PendingContract
      promise="Call-activity parent/child tree in both directions, child failures rolled up, with the explicit max-depth indicator (default 10, “depth limit reached — expand further”) and per-node breadth cap of 50."
      endpoint="GET /api/instances/{engineId}/{instanceId}/hierarchy"
    />
  )
}
