import { Suspense, lazy, useMemo } from 'react'
import type { ComponentType, LazyExoticComponent } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { useEngines } from '../api/useEngines'
import { CopyButton } from '../components/CopyButton'
import { EnvBadge } from '../components/EnvBadge'
import { PendingContract } from './PendingContract'
import type { TabId } from './tabs'
import { DEFAULT_TAB, TAB_IDS, TAB_LABELS, isTabId } from './tabs'

// Every tab is its own chunk AND mounts (= fetches) only when opened — IBM's slow-detail
// lesson (SPEC §4). The heavyweight bpmn-js chunk will join this list with the diagram data.
const TAB_COMPONENTS: Record<TabId, LazyExoticComponent<ComponentType<TabProps>>> = {
  variables: lazy(() => import('./tabs/VariablesTab')),
  'errors-jobs': lazy(() => import('./tabs/ErrorsJobsTab')),
  tasks: lazy(() => import('./tabs/TasksTab')),
  hierarchy: lazy(() => import('./tabs/HierarchyTab')),
  timeline: lazy(() => import('./tabs/TimelineTab')),
  audit: lazy(() => import('./tabs/AuditTab')),
}

interface TabProps {
  engineId: string
  instanceId: string
}

/**
 * Stage 2 (SPEC §4): the full-page, deep-linkable instance route. Vitals header without a
 * click, diagram first (top half), lazy tabs below. Tab state lives in ?tab= so a pasted
 * link reopens the exact view. Vitals/why-stuck/diagram render their honest pending state
 * until the BFF exposes the instance-detail contract.
 */
export function InspectPage() {
  const { engineId = '', instanceId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const engines = useEngines()

  const rawTab = params.get('tab') ?? ''
  const tab: TabId = isTabId(rawTab) ? rawTab : DEFAULT_TAB
  const selectTab = (next: TabId) => {
    const nextParams = new URLSearchParams(params)
    if (next === DEFAULT_TAB) nextParams.delete('tab')
    else nextParams.set('tab', next)
    setParams(nextParams, { replace: true })
  }

  const engine = useMemo(
    () => (engines.data ?? []).find((candidate) => candidate.id === engineId),
    [engines.data, engineId],
  )
  const compositeId = `${engineId}:${instanceId}`
  const deepLink = typeof window !== 'undefined' ? window.location.href : compositeId

  const ActiveTab = TAB_COMPONENTS[tab]

  return (
    <main className="inspect">
      <header className="vitals">
        <div className="vitals-identity">
          <EnvBadge environment={engine?.environment} accentColor={engine?.accentColor} />
          <span className="engine-name">{engine?.name ?? engineId}</span>
          <code className="composite-id">{compositeId}</code>
          <CopyButton text={compositeId} label="copy ID" />
          <CopyButton text={deepLink} label="copy link" />
        </div>
        {engines.isSuccess && engine === undefined && (
          <div className="error-banner" role="alert">
            Unknown engine “{engineId}” — this link may come from an engine that is no longer
            registered.
          </div>
        )}
        <p className="vitals-pending">
          Definition + version, status chip, business key, started/duration and the FAILED/RETRYING
          “why stuck” strip (exception first line, retries state, stacktrace) land with{' '}
          <code>
            GET /api/instances/{'{engineId}'}/{'{instanceId}'}
          </code>
          .
        </p>
      </header>

      <section className="diagram-slot" aria-label="Process diagram">
        <PendingContract
          promise="Read-only bpmn-js diagram, top half: token markers on active activities, red ⚠ badges on dead-letter activities, selection synchronized with the tabs. The viewer (incl. markers/overlays) is implemented in DiagramCanvas.tsx and binds to the XML the moment the endpoint exists."
          endpoint="GET /api/instances/{engineId}/{instanceId}/diagram"
        />
      </section>

      <nav className="tab-bar" role="tablist" aria-label="Instance detail tabs">
        {TAB_IDS.map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={id === tab}
            className={`tab-button${id === tab ? ' tab-active' : ''}`}
            onClick={() => {
              selectTab(id)
            }}
          >
            {TAB_LABELS[id]}
          </button>
        ))}
      </nav>

      <section className="tab-body" role="tabpanel" aria-label={TAB_LABELS[tab]}>
        <Suspense fallback={<div className="zero-state">Loading {TAB_LABELS[tab]}…</div>}>
          <ActiveTab engineId={engineId} instanceId={instanceId} />
        </Suspense>
      </section>
    </main>
  )
}
