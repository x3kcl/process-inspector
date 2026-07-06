// Cascade enumeration for the tier-3 terminate confirm (SPEC §6): call-activity
// descendants die with their parent, so the modal must name them. Derived from the
// hierarchy endpoint — the same tree the Hierarchy tab renders.
import type { HierarchyNode, InstanceHierarchy } from '../api/model'

/**
 * The still-running descendants of `instanceId` in the hierarchy tree, as
 * "<id> (<business key>)" labels. 'unavailable' when the target is not in the tree
 * (depth/breadth caps, resolution failure) — the modal then states the honest fallback.
 */
export function cascadeVictims(
  hierarchy: InstanceHierarchy,
  instanceId: string,
): string[] | 'unavailable' {
  const start = hierarchy.root === undefined ? undefined : findNode(hierarchy.root, instanceId)
  if (start === undefined) return 'unavailable'
  // Truncated children mean the enumeration would lie by omission — say so instead.
  const victims: string[] = []
  if (!collectDescendants(start, victims)) return 'unavailable'
  return victims
}

function findNode(node: HierarchyNode, instanceId: string): HierarchyNode | undefined {
  if (node.processInstanceId === instanceId) return node
  for (const child of node.children ?? []) {
    const found = findNode(child, instanceId)
    if (found !== undefined) return found
  }
  return undefined
}

/** Returns false when a truncated branch makes the enumeration incomplete. */
function collectDescendants(node: HierarchyNode, out: string[]): boolean {
  if (node.childrenTruncated === true) return false
  for (const child of node.children ?? []) {
    if (child.ended !== true) {
      const key = child.businessKey !== undefined ? ` (${child.businessKey})` : ''
      out.push(`${child.processInstanceId ?? '?'}${key}`)
    }
    if (!collectDescendants(child, out)) return false
  }
  return true
}
