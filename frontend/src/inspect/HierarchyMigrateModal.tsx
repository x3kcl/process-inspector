// Hierarchy migration plan builder (#497). The plan API decides everything: this screen stores
// a plan for the root (a read-only walk of the running call-activity tree), sends one decision
// per source definition (target + mapping, or skip), shows the status the plan reports, and
// submits the stored plan id as ONE bulk job. No instance id and no order is sent from here.
import { useState } from 'react'
import { Link } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useProdGuard } from '../actions/guard'
import { parseActionProblem, problemBanner } from '../actions/problem'
import { ActionError } from '../api/actions'
import { useSubmitBulkMigrateHierarchy } from '../api/bulk'
import { api } from '../api/client'
import { useDefinitionVersions } from '../api/migrate'
import type { components } from '../api/schema'
import { useEngines } from '../api/useEngines'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'
import { parseMappingJson } from '../surgery/mappingRules'

type Plan = components['schemas']['HierarchyPlanView']
type PlanGroup = components['schemas']['PlanGroupView']
type PlanNode = components['schemas']['PlanNode']
type Decide = components['schemas']['HierarchyPlanDecide']

interface Props {
  engineId: string
  rootInstanceId: string
  onClose: () => void
}

// Hub's words for the plan API's group statuses. An unknown status is shown as sent.
const STATUS_WORD: Partial<Record<string, string>> = {
  PENDING: 'Pending',
  ANALYZING: 'Analyzing…',
  CONFIGURED: 'Configured',
  ANALYSIS_FAILED: 'Analysis failed',
  SKIPPED: 'Skipped',
}
const statusWord = (status: string | undefined) => STATUS_WORD[status ?? ''] ?? status ?? '?'
const groupLabel = (group: PlanGroup) =>
  `${group.definitionKey ?? group.definitionId ?? '?'} v${String(group.version ?? '?')}`

export function HierarchyMigrateModal({ engineId, rootInstanceId, onClose }: Props) {
  const engines = useEngines()
  const toast = useToast()
  const drawer = useOpsDrawer()
  const queryClient = useQueryClient()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment

  // Opening the screen stores a fresh plan (the tree is read now, not from a cache).
  const created = useQuery<Plan, ActionError>({
    queryKey: ['hierarchy-plan-new', engineId, rootInstanceId],
    queryFn: async () => {
      const { data, error, response } = await api.POST('/api/migration/hierarchy/plans', {
        params: { query: { engineId } },
        body: { rootInstanceId },
      })
      if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
      return data
    },
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
  })
  const planId = created.data?.id
  const planKey = ['hierarchy-plan', planId]

  // A configured group is estimated in the background; re-read the stored plan until it settles.
  const stored = useQuery<Plan, ActionError>({
    queryKey: planKey,
    enabled: planId !== undefined,
    initialData: created.data,
    staleTime: Infinity,
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/migration/hierarchy/plans/{id}', {
        params: { path: { id: planId ?? '' } },
      })
      if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
      return data
    },
    refetchInterval: (query) =>
      (query.state.data?.groups ?? []).some((group) => group.status === 'ANALYZING') ? 1000 : false,
  })
  const plan = stored.data ?? created.data

  const decide = useMutation<Plan, ActionError, Decide>({
    retry: false,
    mutationFn: async (body) => {
      const { data, error, response } = await api.PUT(
        '/api/migration/hierarchy/plans/{id}/groups',
        { params: { path: { id: planId ?? '' } }, body },
      )
      if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
      return data
    },
    onSuccess: (data) => {
      queryClient.setQueryData(planKey, data)
    },
  })

  const executable = plan?.executable === true ? (plan.executableCount ?? 0) : 0
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken: executable > 0 ? String(executable) : undefined,
  })
  const submit = useSubmitBulkMigrateHierarchy()
  const migrate = () => {
    if (planId === undefined) return
    submit.mutate(
      {
        planId,
        reason: guard.reason.trim(),
        ticketId: ticketValue(guard.ticket),
        confirmedCount: guard.needsToken ? Number(guard.typed) : undefined,
      },
      {
        onSuccess: (job) => {
          toast({
            kind: 'success',
            text: `Hierarchy migration started over ${String(job.totalItems ?? 0)} instance${
              job.totalItems === 1 ? '' : 's'
            }, children first. Progress in the operations drawer.`,
          })
          if (job.id !== undefined) drawer.focusJob(job.id)
          onClose()
        },
      },
    )
  }

  const groups = plan?.groups ?? []
  const byDefinition = new Map(groups.map((group) => [group.definitionId, group]))
  const nodes = plan?.nodes ?? []
  const roots = nodes.filter((node) => node.parentInstanceId == null)

  const renderNode = (node: PlanNode) => {
    const group = byDefinition.get(node.definitionId)
    const children = nodes.filter((child) => child.parentInstanceId === node.instanceId)
    return (
      <li key={node.instanceId} className="hier-node">
        <span className="hier-row">
          <Link to={`/inspect/${engineId}/${node.instanceId ?? ''}`}>{node.instanceId}</Link>
          <span className="version-chip">
            {group !== undefined ? groupLabel(group) : node.definitionId}
          </span>
          {node.businessKey != null && <code className="hier-key">{node.businessKey}</code>}
          <span className="value-muted">
            level {String(node.depth ?? 0)} · {statusWord(group?.status)}
          </span>
        </span>
        {children.length > 0 && <ul className="hier-tree">{children.map(renderNode)}</ul>}
      </li>
    )
  }

  return (
    <ModalShell
      title="Migrate this hierarchy — children first, then parent"
      environment={environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="primary"
            disabled={executable === 0 || !guard.reasonOk || !guard.tokenOk || submit.isPending}
            onClick={migrate}
          >
            Migrate {executable > 0 ? String(executable) : ''}
          </button>
        </>
      }
    >
      <p>
        The running call-activity tree under <code>{`${engineId}:${rootInstanceId}`}</code>, grouped
        by definition. Set a target version (and a mapping if needed) for each definition, or skip
        it. Nothing is migrated until you submit; the job then runs the deepest level first.
      </p>
      {created.isPending && <p role="status">Reading the running tree…</p>}
      {created.isError && <p role="alert">{problemBanner(created.error.problem)}</p>}
      {plan !== undefined && (
        <>
          <ul className="hier-tree hier-root" aria-label="Plan tree">
            {roots.map(renderNode)}
          </ul>
          {(plan.truncations ?? []).length > 0 && (
            <div className="callout callout-amber" role="alert">
              <p>The tree was not read completely, so this plan cannot be submitted:</p>
              <ul>
                {(plan.truncations ?? []).map((cut) => (
                  <li key={`${cut.instanceId ?? ''}-${cut.kind ?? ''}`}>
                    Stopped at {engineId}:{cut.instanceId} ({cut.kind}):{' '}
                    {String(cut.enumerated ?? 0)} of {String(cut.total ?? 0)} read
                  </li>
                ))}
              </ul>
            </div>
          )}
          {(plan.circularReferences ?? []).length > 0 && (
            <div className="callout callout-info">
              <p>Circular references (reported, still part of the plan):</p>
              <ul>
                {(plan.circularReferences ?? []).map((ref) => (
                  <li key={ref.instanceId}>
                    {ref.definitionKey}: {engineId}:{ref.instanceId} runs under {engineId}:
                    {ref.ancestorInstanceId} of the same definition (level {String(ref.depth ?? 0)})
                  </li>
                ))}
              </ul>
            </div>
          )}
          {groups.map((group) => (
            <GroupRow
              key={group.definitionId}
              engineId={engineId}
              group={group}
              busy={decide.isPending}
              onDecide={(body) => {
                decide.mutate(body)
              }}
            />
          ))}
          {decide.isError && <p role="alert">{problemBanner(decide.error.problem)}</p>}
          {stored.isError && <p role="alert">{problemBanner(stored.error.problem)}</p>}
          <p role="status">
            {plan.notExecutableReason ??
              `${String(executable)} instance${executable === 1 ? '' : 's'} will be sent · ${String(
                plan.notSentCount ?? 0,
              )} not sent`}
          </p>
          {plan.banner != null && <p className="strip-note">{plan.banner}</p>}
        </>
      )}
      <GuardFields guard={guard} expectedToken={executable > 0 ? String(executable) : undefined} />
      <TicketField value={guard.ticket} onChange={guard.setTicket} />
      {submit.isError && <p role="alert">{problemBanner(submit.error.problem)}</p>}
    </ModalShell>
  )
}

interface GroupRowProps {
  engineId: string
  group: PlanGroup
  busy: boolean
  onDecide: (body: Decide) => void
}

/** One source definition: its status, and the two decisions the plan API takes for it. */
function GroupRow({ engineId, group, busy, onDecide }: GroupRowProps) {
  const label = groupLabel(group)
  const versions = useDefinitionVersions(engineId, group.definitionKey)
  const targets = (versions.data?.versions ?? []).filter(
    (version) => version.definitionId !== group.definitionId,
  )
  const [chosen, setChosen] = useState<string | undefined>(undefined)
  const [mappingText, setMappingText] = useState('')
  const target =
    chosen ??
    group.targetDefinitionId ??
    targets.find((version) => version.latest === true)?.definitionId ??
    targets[0]?.definitionId
  const mapping =
    mappingText.trim() === '' ? { ok: true as const, mappings: [] } : parseMappingJson(mappingText)
  const targetVersion = targets.find((v) => v.definitionId === group.targetDefinitionId)?.version

  return (
    <fieldset className="modal-field">
      <legend>{label}</legend>
      <p>
        <span className="status-chip">{statusWord(group.status)}</span>{' '}
        {String(group.instanceCount ?? 0)} instance{group.instanceCount === 1 ? '' : 's'} · level
        {group.minDepth === group.maxDepth
          ? ` ${String(group.minDepth ?? 0)}`
          : `s ${String(group.minDepth ?? 0)}–${String(group.maxDepth ?? 0)}`}
        {group.status === 'CONFIGURED' &&
          ` · → ${targetVersion !== undefined ? `v${String(targetVersion)}` : (group.targetDefinitionId ?? '?')} · ${String(
            group.executableCount ?? 0,
          )} will be sent · ${String(group.blockerCount ?? 0)} not sent`}
      </p>
      {versions.isError && <p role="alert">The version list could not be loaded.</p>}
      {versions.isSuccess && targets.length === 0 && (
        <p className="strip-note">No other deployed version to migrate to — skip it.</p>
      )}
      <label>
        Target version{' '}
        <select
          aria-label={`Target version for ${label}`}
          value={target ?? ''}
          onChange={(event) => {
            setChosen(event.target.value)
          }}
        >
          {targets.map((version) => (
            <option key={version.definitionId} value={version.definitionId ?? ''}>
              v{String(version.version ?? '?')}
              {version.latest === true ? ' (latest)' : ''}
            </option>
          ))}
        </select>
      </label>
      <label>
        Mapping (optional — a JSON array, as in the single-instance editor&apos;s Expert mode)
        <textarea
          aria-label={`Mapping JSON for ${label}`}
          rows={2}
          spellCheck={false}
          placeholder='[{"fromActivityId":"oldTask","toActivityId":"newTask"}]'
          value={mappingText}
          onChange={(event) => {
            setMappingText(event.target.value)
          }}
        />
      </label>
      {!mapping.ok && (
        <p className="strip-note" role="alert">
          {mapping.reason}
        </p>
      )}
      <button
        type="button"
        disabled={busy || target === undefined || !mapping.ok}
        onClick={() => {
          if (!mapping.ok || target === undefined) return
          onDecide({
            definitionId: group.definitionId,
            toDefinitionId: target,
            mappings: mapping.mappings,
          })
        }}
      >
        Configure
      </button>{' '}
      <button
        type="button"
        disabled={busy}
        onClick={() => {
          onDecide({ definitionId: group.definitionId, skip: true })
        }}
      >
        Skip
      </button>
    </fieldset>
  )
}
