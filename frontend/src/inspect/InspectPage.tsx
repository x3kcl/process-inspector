import { Suspense, lazy, useCallback, useMemo, useRef, useState } from 'react'
import type { ComponentType, LazyExoticComponent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import type { InstanceDetail } from '../api/model'
import { useEngines } from '../api/useEngines'
import { CopyButton } from '../components/CopyButton'
import { EnvBadge } from '../components/EnvBadge'
import { StatusChip } from '../components/StatusChip'
import { formatSeconds } from '../lib/format'
import { Ts } from '../lib/Ts'
import { DetailTabBar } from './DetailTabBar'
import { DiagramCanvas } from './DiagramCanvas'
import { instanceLoadFailureCopy } from './loadFailure'
import type { DivergenceMarkerSet } from './comparison/diffFormat'
import { InstanceActions } from './InstanceActions'
import type { TabId } from './tabs'
import { DEFAULT_TAB, TAB_LABELS, isTabId } from './tabs'
import { buildErrorTicketText, buildTicketText } from './ticket'
import { useInstanceDiagram, useInstanceVitals } from './useInstanceQueries'

// Every tab is its own chunk AND mounts (= fetches) only when opened — IBM's slow-detail
// lesson (SPEC §4). bpmn-js ships with the page chunk; its data still loads lazily.
const TAB_COMPONENTS: Record<TabId, LazyExoticComponent<ComponentType<TabProps>>> = {
  variables: lazy(() => import('./tabs/VariablesTab')),
  'errors-jobs': lazy(() => import('./tabs/ErrorsJobsTab')),
  tasks: lazy(() => import('./tabs/TasksTab')),
  hierarchy: lazy(() => import('./tabs/HierarchyTab')),
  timeline: lazy(() => import('./tabs/TimelineTab')),
  comparison: lazy(() => import('./comparison/ComparisonTab')),
  audit: lazy(() => import('./tabs/AuditTab')),
}

export interface TabProps {
  engineId: string
  instanceId: string
  /** Diagram↔tab selection sync (SPEC §4): the activity selected on the canvas. */
  selectedActivityId?: string
  /** Tabs report a selection back ("show on diagram") through this. */
  onShowOnDiagram?: (activityId: string) => void
  /**
   * Sibling-diff (SPEC §5.2, Option B): the Compare tab reports its path divergence up so the
   * single top diagram renders the ▲/△ overlay. undefined clears it. Ignored by other tabs.
   */
  onDivergence?: (markers: DivergenceMarkerSet | undefined) => void
}

/**
 * Stage 2 (SPEC §4): the full-page, deep-linkable instance route. Vitals header without a
 * click, diagram first (top half), lazy tabs below. Tab state lives in ?tab= so a pasted
 * link reopens the exact view.
 */
export function InspectPage() {
  const { engineId = '', instanceId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const engines = useEngines()
  const vitals = useInstanceVitals(engineId, instanceId)

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

  // Diagram↔tab selection sync (SPEC §4): one shared selection, driven from both sides.
  const [selectedActivityId, setSelectedActivityId] = useState<string>()
  // Collapsible diagram: on a small process the fixed-height canvas starves the tab section
  // below it, so the operator can fold the diagram away and hand the full height to the tabs.
  // The choice is sticky across instances (one preference for the whole detail view).
  const [diagramCollapsed, setDiagramCollapsed] = useState(readDiagramCollapsed)
  const toggleDiagram = useCallback(() => {
    setDiagramCollapsed((collapsed) => {
      const next = !collapsed
      writeDiagramCollapsed(next)
      return next
    })
  }, [])
  const diagramSectionRef = useRef<HTMLElement | null>(null)
  const onDiagramSelect = useCallback(
    (activityId: string, isDeadLetter: boolean) => {
      setSelectedActivityId(activityId)
      // A dead-letter node click jumps straight to its evidence in Errors & Jobs.
      if (isDeadLetter) selectTab('errors-jobs')
    },
    // selectTab is recreated per render but only closes over stable router state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [params],
  )
  const onShowOnDiagram = useCallback((activityId: string) => {
    setSelectedActivityId(activityId)
    // "Show on diagram" only makes sense with the diagram visible — unfold it first.
    setDiagramCollapsed(false)
    writeDiagramCollapsed(false)
    diagramSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [])

  // Sibling-diff (SPEC §5.2, Option B): the Compare tab reports its path divergence UP so the
  // single always-on diagram carries the ▲/△ overlay — no second bpmn canvas. Cleared on the
  // tab's unmount, so leaving Compare reverts the diagram to its plain instance markers.
  const [divergence, setDivergence] = useState<DivergenceMarkerSet>()

  const ActiveTab = TAB_COMPONENTS[tab]

  return (
    <main className="inspect">
      <header className="vitals">
        <div className="vitals-identity">
          <EnvBadge
            environment={engine?.environment}
            accentColor={engine?.accentColor}
            mode={engine?.mode}
            lifecycle={engine?.lifecycle}
          />
          <span className="engine-name">{engine?.name ?? engineId}</span>
          <code className="composite-id">{compositeId}</code>
          <CopyButton text={compositeId} label="copy ID" />
          <CopyButton text={deepLink} label="copy link" />
          {vitals.data !== undefined && (
            <CopyButton
              text={buildTicketText(vitals.data, compositeId, deepLink)}
              label="copy for ticket"
            />
          )}
          {vitals.isError && (
            // W1#6 (R-AUD-04): the errored page still yields a support-ready ticket — the
            // error sentence carries the quotable request ID (appended by ApiError).
            <CopyButton
              text={buildErrorTicketText(compositeId, vitals.error.message, deepLink)}
              label="copy for ticket"
            />
          )}
          {vitals.data?.telemetryUrl !== undefined && (
            <a
              className="open-logs"
              href={vitals.data.telemetryUrl}
              target="_blank"
              rel="noreferrer"
            >
              open logs ↗
            </a>
          )}
        </div>
        {engines.isSuccess && engine === undefined && (
          <div className="error-banner" role="alert">
            Unknown engine “{engineId}” — this link may come from an engine that is no longer
            registered.
          </div>
        )}
        {vitals.isPending && <p className="zero-state">Loading instance vitals…</p>}
        {vitals.isError && (
          // W2 #4 (T12): coverage-parameterized honesty — definitive when the engine
          // answered (404), hedged ONLY when it did not; never both at once.
          <div className="error-banner" role="alert">
            {vitals.error.message} —{' '}
            {instanceLoadFailureCopy(
              vitals.error instanceof ApiError ? vitals.error.status : undefined,
            )}
          </div>
        )}
        {vitals.data !== undefined && (
          <VitalsBody
            vitals={vitals.data}
            onCompare={() => {
              selectTab('comparison')
            }}
          />
        )}
        {vitals.data !== undefined && (
          <InstanceActions
            engineId={engineId}
            instanceId={instanceId}
            vitals={vitals.data}
            engine={engine}
          />
        )}
      </header>

      <section
        className={`diagram-slot${diagramCollapsed ? ' diagram-collapsed' : ''}`}
        aria-label="Process diagram"
        ref={diagramSectionRef}
      >
        <div className="diagram-slot-bar">
          <button
            type="button"
            className="diagram-toggle"
            aria-expanded={!diagramCollapsed}
            onClick={toggleDiagram}
          >
            <span aria-hidden="true">{diagramCollapsed ? '▸' : '▾'}</span>
            {diagramCollapsed ? 'Show diagram' : 'Hide diagram'}
          </button>
        </div>
        {!diagramCollapsed && (
          <DiagramSlot
            engineId={engineId}
            instanceId={instanceId}
            selectedActivityId={selectedActivityId}
            onSelect={onDiagramSelect}
            divergence={divergence}
          />
        )}
      </section>

      {/* R-UXQ-02: APG tablist with arrow-key roving focus, extracted for direct
          keyboard-event testing. */}
      <DetailTabBar active={tab} onSelect={selectTab} />

      <section className="tab-body" role="tabpanel" aria-label={TAB_LABELS[tab]}>
        <Suspense fallback={<div className="zero-state">Loading {TAB_LABELS[tab]}…</div>}>
          <ActiveTab
            engineId={engineId}
            instanceId={instanceId}
            selectedActivityId={selectedActivityId}
            onShowOnDiagram={onShowOnDiagram}
            onDivergence={setDivergence}
          />
        </Suspense>
      </section>
    </main>
  )
}

const DIAGRAM_COLLAPSED_KEY = 'inspect.diagramCollapsed'

/** Sticky diagram fold state — best-effort; a locked-down localStorage just means no memory. */
function readDiagramCollapsed(): boolean {
  try {
    return localStorage.getItem(DIAGRAM_COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}
function writeDiagramCollapsed(collapsed: boolean): void {
  try {
    if (collapsed) localStorage.setItem(DIAGRAM_COLLAPSED_KEY, '1')
    else localStorage.removeItem(DIAGRAM_COLLAPSED_KEY)
  } catch {
    // No persistence available (private mode / quota) — the in-memory state still works.
  }
}

/** Everything the operator sees WITHOUT a tab or a click (SPEC §4). */
function VitalsBody({ vitals, onCompare }: { vitals: InstanceDetail; onCompare: () => void }) {
  const definition =
    vitals.definitionName ?? vitals.definitionKey ?? vitals.processDefinitionId ?? '(unknown)'
  return (
    <>
      <div className="vitals-grid">
        <span className="vital">
          <span className="vital-label">Definition</span>
          {definition}
          {vitals.definitionVersion !== undefined && (
            <span className="version-chip" title={vitals.processDefinitionId}>
              v{vitals.definitionVersion}
            </span>
          )}
        </span>
        <span className="vital">
          <span className="vital-label">Status</span>
          <StatusChip
            status={vitals.status}
            flags={vitals.flags}
            engineId={vitals.engineId}
            instanceId={vitals.processInstanceId}
          />
        </span>
        <span className="vital">
          <span className="vital-label">Business key</span>
          {vitals.businessKey !== undefined && vitals.businessKey !== '' ? (
            <code>{vitals.businessKey}</code>
          ) : (
            <span className="value-muted">(none)</span>
          )}
        </span>
        <span className="vital">
          <span className="vital-label">Started</span>
          <Ts iso={vitals.startTime} relative />
          {vitals.startedBy !== undefined && ` by ${vitals.startedBy}`}
        </span>
        {vitals.endTime !== undefined && (
          <span className="vital">
            <span className="vital-label">Ended</span>
            <Ts iso={vitals.endTime} relative />
            {vitals.durationMs !== undefined &&
              ` (took ${formatSeconds(Math.round(vitals.durationMs / 1000))})`}
          </span>
        )}
        {vitals.superProcessInstanceId !== undefined && (
          <span className="vital">
            <span className="vital-label">Called by</span>
            <Link to={`/inspect/${vitals.engineId ?? ''}/${vitals.superProcessInstanceId}`}>
              parent instance <code>{vitals.superProcessInstanceId}</code>
            </Link>
          </span>
        )}
        {vitals.currentActivities !== undefined && vitals.currentActivities.length > 0 && (
          <span className="vital">
            <span className="vital-label">Currently at</span>
            {vitals.currentActivities
              .map((activity) => activity.activityName ?? activity.activityId ?? '?')
              .join(', ')}
          </span>
        )}
      </div>

      {vitals.whyStuck !== undefined && <WhyStuckStrip vitals={vitals} onCompare={onCompare} />}

      {vitals.waitingFor !== undefined && vitals.waitingFor.length > 0 && (
        <p className="waiting-for">
          Waiting for:{' '}
          {vitals.waitingFor.map((wait, index) => (
            <span key={`${wait.kind ?? ''}:${String(index)}`} className="waiting-chip">
              {wait.kind === 'TIMER' ? (
                <>
                  timer
                  {wait.dueDate !== undefined && (
                    <>
                      {' due '}
                      <Ts iso={wait.dueDate} relative />
                    </>
                  )}
                </>
              ) : (
                `${(wait.kind ?? 'event').toLowerCase()} “${wait.name ?? '?'}”`
              )}
              {wait.activityId !== undefined && ` at ${wait.activityId}`}
            </span>
          ))}
        </p>
      )}
    </>
  )
}

/** SPEC §4: exception first line, failing activity, retries state — present iff stuck. */
function WhyStuckStrip({ vitals, onCompare }: { vitals: InstanceDetail; onCompare: () => void }) {
  const whyStuck = vitals.whyStuck
  if (whyStuck === undefined) return null
  const deadLetter = whyStuck.deadLetterJobs ?? 0
  const retrying = whyStuck.retryingJobs ?? 0
  return (
    <div className="why-stuck" role="alert">
      <p className="why-stuck-headline">
        {whyStuck.exceptionFirstLine !== undefined ? (
          <code>{whyStuck.exceptionFirstLine}</code>
        ) : (
          'Failing without an exception message'
        )}
      </p>
      <p className="why-stuck-meta">
        {whyStuck.failingActivityId !== undefined && (
          <>
            at <code>{whyStuck.failingActivityId}</code> ·{' '}
          </>
        )}
        {whyStuck.failureTime !== undefined && (
          <>
            last failure <Ts iso={whyStuck.failureTime} relative copyIso /> ·{' '}
          </>
        )}
        {deadLetter > 0 && (
          <>
            {deadLetter} dead-letter job{deadLetter === 1 ? '' : 's'} (retries exhausted)
          </>
        )}
        {deadLetter > 0 && retrying > 0 && ' · '}
        {retrying > 0 && (
          <>
            {retrying} retrying
            {whyStuck.retriesRemaining !== undefined &&
              ` — ${String(whyStuck.retriesRemaining)} retries left`}
            {whyStuck.nextRetryDue !== undefined && (
              <>
                {', next attempt '}
                <Ts iso={whyStuck.nextRetryDue} relative />
              </>
            )}
          </>
        )}
        {' · '}details in the Errors &amp; Jobs tab
      </p>
      <button type="button" className="compare-cta" onClick={onCompare}>
        Compare with a sibling — why did this one fail? →
      </button>
    </div>
  )
}

/** Diagram first, top half (SPEC §4): read-only bpmn-js with token + dead-letter markers. */
function DiagramSlot({
  engineId,
  instanceId,
  selectedActivityId,
  onSelect,
  divergence,
}: {
  engineId: string
  instanceId: string
  selectedActivityId?: string
  onSelect: (activityId: string, isDeadLetter: boolean) => void
  /** Present only while the Compare tab is active — overlays ▲/△ on this one canvas. */
  divergence?: DivergenceMarkerSet
}) {
  const diagram = useInstanceDiagram(engineId, instanceId)
  // One stable markers object per data change — DiagramCanvas re-imports on change. When the
  // Compare tab is active, the sibling-diff overlay rides the SAME canvas (Option B).
  const markers = useMemo(
    () => ({
      activeActivityIds: diagram.data?.activeActivityIds ?? [],
      deadLetterActivityIds: diagram.data?.deadLetterActivityIds ?? [],
      subjectOnlyActivityIds: divergence?.subjectOnlyActivityIds ?? [],
      siblingOnlyActivityIds: divergence?.siblingOnlyActivityIds ?? [],
    }),
    [diagram.data, divergence],
  )
  const handleSelect = useCallback(
    (activityId: string) => {
      onSelect(activityId, markers.deadLetterActivityIds.includes(activityId))
    },
    [onSelect, markers],
  )
  if (diagram.isPending) return <div className="zero-state">Loading process diagram…</div>
  if (diagram.isError) {
    return (
      <div className="zero-state zero-warn">
        No diagram: {diagram.error.message}. The instance data below is unaffected.
      </div>
    )
  }
  if (diagram.data.xml === undefined || diagram.data.xml === '') {
    return <div className="zero-state">The engine holds no BPMN XML for this definition.</div>
  }
  return (
    <DiagramCanvas
      xml={diagram.data.xml}
      markers={markers}
      onSelectActivity={handleSelect}
      selectedActivityId={selectedActivityId}
    />
  )
}
