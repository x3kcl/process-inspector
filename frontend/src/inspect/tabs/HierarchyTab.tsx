import { Link } from 'react-router'
import type { HierarchyNode } from '../../api/model'
import { formatDateTime } from '../../lib/format'
import { useInstanceHierarchy } from '../useInstanceQueries'

interface Props {
  engineId: string
  instanceId: string
}

/**
 * The call-activity tree, BOTH directions (SPEC §4): rendered from the root the backend
 * walked up to, down through depth-capped, breadth-capped children. Every cap is an
 * explicit marker (R-SEM-19) — counts stay exact via childTotal, never a silent cut.
 */
export default function HierarchyTab({ engineId, instanceId }: Props) {
  const query = useInstanceHierarchy(engineId, instanceId)

  if (query.isPending) return <div className="zero-state">Loading the call-activity tree…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Hierarchy unavailable: {query.error.message}
      </div>
    )
  }
  const { root, depthLimitReached = false, maxDepth, breadthCap } = query.data
  if (root === undefined) {
    return <div className="zero-state">This instance stands alone — no parents, no children.</div>
  }
  return (
    <div className="hierarchy-tab">
      {depthLimitReached && (
        <p className="strip-note">
          Depth limit reached (max {maxDepth ?? '?'} levels rendered) — deeper children exist. Open
          a leaf node to continue from there.
        </p>
      )}
      <ul className="hier-tree hier-root">
        <HierarchyNodeView node={root} engineId={engineId} breadthCap={breadthCap} />
      </ul>
    </div>
  )
}

function HierarchyNodeView({
  node,
  engineId,
  breadthCap,
}: {
  node: HierarchyNode
  engineId: string
  breadthCap: number | undefined
}) {
  const children = node.children ?? []
  const shown = children.length
  const total = node.childTotal ?? 0
  const label = node.definitionName ?? node.definitionKey ?? node.processInstanceId ?? '?'
  return (
    <li className={`hier-node${node.requested === true ? ' hier-requested' : ''}`}>
      <span className="hier-row">
        {node.requested === true ? (
          <strong title="the instance you navigated from">{label}</strong>
        ) : (
          <Link to={`/inspect/${engineId}/${node.processInstanceId ?? ''}`}>{label}</Link>
        )}
        {node.definitionVersion !== undefined && (
          <span className="version-chip">v{node.definitionVersion}</span>
        )}
        {node.businessKey !== undefined && <code className="hier-key">{node.businessKey}</code>}
        {node.hasDeadLetterJobs === true && (
          <span className="status-chip failed" title="this node holds dead-letter jobs">
            FAILED
          </span>
        )}
        {node.ended === true && <span className="status-chip completed">ended</span>}
        <span className="hier-times value-muted">
          {formatDateTime(node.startTime)}
          {node.endTime !== undefined && ` → ${formatDateTime(node.endTime)}`}
        </span>
      </span>
      {(shown > 0 || total > shown) && (
        <ul className="hier-tree">
          {children.map((child) => (
            <HierarchyNodeView
              key={child.processInstanceId ?? label}
              node={child}
              engineId={engineId}
              breadthCap={breadthCap}
            />
          ))}
          {total > shown && (
            <li className="hier-node">
              <span className="strip-note">
                +{total - shown} more child{total - shown === 1 ? '' : 'ren'} not rendered (cap
                {breadthCap !== undefined ? ` ${String(breadthCap)}` : ''}/node) — count is exact,
                narrow via search to see them.
              </span>
            </li>
          )}
        </ul>
      )}
    </li>
  )
}
