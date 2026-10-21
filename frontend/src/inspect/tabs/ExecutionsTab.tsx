import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import type { components } from '../../api/schema'

type Tree = components['schemas']['ExecutionTree']
type Node = components['schemas']['ExecutionNode']

interface Props {
  engineId: string
  instanceId: string
  onShowOnDiagram?: (activityId: string) => void
}

/** Token list nested by parentId. Selecting a row highlights it. A move still cancels every token on that activity. */
export default function ExecutionsTab({ engineId, instanceId, onShowOnDiagram }: Props) {
  const query = useQuery({
    queryKey: ['instance', engineId, instanceId, 'executions'],
    queryFn: async (): Promise<Tree> => {
      const { data, error, response } = await api.GET(
        '/api/instances/{engineId}/{instanceId}/executions',
        { params: { path: { engineId, instanceId } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  if (query.isPending) return <div className="zero-state">Loading executions…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Executions unavailable: {query.error.message}
      </div>
    )
  }
  const nodes = query.data.nodes ?? []
  const roots = nodes.filter(
    (node) => node.parentId === undefined || !nodes.some((other) => other.id === node.parentId),
  )
  if (roots.length === 0) {
    return <div className="zero-state">No live executions — this case is not running.</div>
  }
  return (
    <div>
      {query.data.pageCapped && (
        <p>Lower bound. The engine page was shorter than the execution total.</p>
      )}
      <p>A move cancels every token on the chosen activity, not only the row you select.</p>
      <ul>
        {roots.map((node) => (
          <ExecutionItem key={node.id} node={node} nodes={nodes} onShow={onShowOnDiagram} />
        ))}
      </ul>
    </div>
  )
}

function ExecutionItem({
  node,
  nodes,
  onShow,
}: {
  node: Node
  nodes: Node[]
  onShow?: (activityId: string) => void
}) {
  const activityId = node.activityId ?? ''
  const children = nodes.filter((candidate) => candidate.parentId === node.id)
  return (
    <li>
      <button
        type="button"
        onClick={() => {
          if (activityId !== '' && onShow !== undefined) onShow(activityId)
        }}
      >
        {activityId || '(no activity)'} <code>{node.id}</code>
        {node.suspended ? ' suspended' : ''}
      </button>
      {node.capped && (
        <span>
          {' '}
          showing {children.length} of {node.childTotal ?? children.length}
        </span>
      )}
      {children.length > 0 && (
        <ul>
          {children.map((child) => (
            <ExecutionItem key={child.id} node={child} nodes={nodes} onShow={onShow} />
          ))}
        </ul>
      )}
    </li>
  )
}
