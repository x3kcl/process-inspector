// Issue #102 "rerun from activity": the documented v1.1 shape (TEST-SCENARIOS.md
// TS-VERB-09, IMPLEMENTATION-PLAN.md "v1.1 — Flow surgery") — variable edits applied,
// THEN move, as one guided composite on the SAME still-live instance, both halves
// audited separately through the existing edit-variable and change-state rails
// unchanged. Deliberately NOT the "terminate + restart at activity X" reading of the
// issue's literal title — Flowable's REST API has no start-at-activity primitive, that
// would need a brand new ADMIN/tier-3/IRREVERSIBLE composite, and it isn't what v1.1's
// own docs ever scoped (confirmed with the user before building).
//
// Three phases, no new backend endpoint: 'edit' (this file's own lightweight variable
// picker + scalar widgets — case-scope only, JSON variables are out of this composite's
// scope, edit those from the Variables tab first) → 'verify' (the EXISTING VerifyModal,
// unchanged, dispatches edit-variable) → 'move' (the EXISTING ChangeStateModal,
// unchanged, including its own issue #102 diagram-click picker).
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useInstanceAction } from '../api/actions'
import type { ActionRequest } from '../api/actions'
import type { EngineDto, InstanceDetail } from '../api/model'
import { fetchInstanceVariable, fetchInstanceVariables } from '../api/queries'
import { VERBS } from '../actions/catalog'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import { BooleanWidget, NumberWidget, TextWidget } from '../inspect/variables/editor/FormMode'
import { parseNumberInput } from '../inspect/variables/editor/editState'
import { VerifyModal } from '../inspect/variables/editor/VerifyModal'
import { ChangeStateModal } from './ChangeStateModal'

interface Props {
  engineId: string
  instanceId: string
  vitals: InstanceDetail
  engine?: EngineDto
  onClose: () => void
}

type ScalarType = 'string' | 'long' | 'double' | 'boolean'

export function RerunFromActivityModal({ engineId, instanceId, vitals, engine, onClose }: Props) {
  const toast = useToast()
  const action = useInstanceAction(engineId, instanceId)
  const [phase, setPhase] = useState<'edit' | 'verify' | 'move'>('edit')
  const [name, setName] = useState('')
  const [request, setRequest] = useState<ActionRequest | null>(null)
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`

  if (phase === 'move') {
    // Unchanged — including its own #102 diagram-click picker. The edit already landed
    // and was already audited; this second half is its own independent, already-proven
    // preview-first flow, exactly as if launched standalone.
    return (
      <ChangeStateModal
        engineId={engineId}
        instanceId={instanceId}
        vitals={vitals}
        engine={engine}
        onClose={onClose}
      />
    )
  }

  if (phase === 'verify' && request !== null) {
    const businessKey = vitals.businessKey
    const targetLabel = businessKey !== undefined && businessKey !== '' ? businessKey : instanceId
    return (
      <VerifyModal
        engineId={engineId}
        instanceId={instanceId}
        engine={engine}
        targetLabel={targetLabel}
        request={request}
        typeLabel={TYPE_LABELS[request.variable?.type as ScalarType]}
        typeChanged={null}
        pending={action.isPending}
        problem={action.error?.problem}
        onDispatch={(reason, ticketId) => {
          action.mutate(
            { verb: VERBS.editVariable.verb, body: { ...request, reason, ticketId } },
            {
              onSuccess: (result) => {
                toast({
                  kind: 'success',
                  text: result.deltaStatement ?? `${name} updated`,
                  auditPath,
                })
                // Both halves audited separately (TS-VERB-09) — the edit already landed;
                // now guide straight into the move half, never automatic.
                setPhase('move')
              },
            },
          )
        }}
        onStartOver={() => {
          setPhase('edit')
          setRequest(null)
          action.reset()
        }}
        onClose={() => {
          action.reset()
          setPhase('edit')
        }}
      />
    )
  }

  return (
    <EditStep
      engineId={engineId}
      instanceId={instanceId}
      engine={engine}
      name={name}
      onNameChange={setName}
      onClose={onClose}
      onReview={(built) => {
        setRequest(built)
        setPhase('verify')
      }}
    />
  )
}

const TYPE_LABELS: Record<ScalarType, string> = {
  string: 'text',
  long: 'number',
  double: 'number',
  boolean: 'yes-no',
}

function inferScalarType(value: unknown): ScalarType {
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'number') return Number.isInteger(value) ? 'long' : 'double'
  return 'string'
}

function EditStep({
  engineId,
  instanceId,
  engine,
  name,
  onNameChange,
  onClose,
  onReview,
}: {
  engineId: string
  instanceId: string
  engine?: EngineDto
  name: string
  onNameChange: (name: string) => void
  onClose: () => void
  onReview: (request: ActionRequest) => void
}) {
  const variables = useQuery({
    queryKey: ['rerun-from-activity-variables', engineId, instanceId],
    queryFn: () => fetchInstanceVariables({ engineId, instanceId }),
  })
  // Case-scope only (executionId undefined) — step-local variables have a narrower
  // audience and their own dedicated editor already; keeping this composite's picker to
  // the common case (a case-wide value driving the routing decision) instead of also
  // threading executionId scoping through a second, parallel picker.
  const caseScoped = (variables.data?.processVariables ?? []).filter(
    (entry) => entry.executionId === undefined && entry.name !== undefined,
  )
  const selected = caseScoped.find((entry) => entry.name === name)
  const jsonValued =
    selected !== undefined && selected.value !== null && typeof selected.value === 'object'

  // Unconditional full-value fetch once a variable is picked (§4a discipline: a
  // truncated ledger projection must never be staged as an edit's starting point).
  const full = useQuery({
    queryKey: ['rerun-from-activity-full', engineId, instanceId, name],
    queryFn: () => fetchInstanceVariable({ engineId, instanceId }, name),
    enabled: name !== '' && !jsonValued,
    staleTime: 0,
    gcTime: 0,
  })

  const originalValue = full.data?.value
  const scalarType = originalValue === undefined ? 'string' : inferScalarType(originalValue)
  const [textValue, setTextValue] = useState('')
  const [numberRaw, setNumberRaw] = useState('')
  const [boolValue, setBoolValue] = useState<boolean | undefined>(undefined)

  // The number path reuses the codebase's OWN parser (editState.ts) rather than a bare
  // Number(raw) — that would let "12abc" (NaN) or "1e999" (Infinity) through as "valid"
  // while NumberWidget shows its own visible parse error, letting a submit dispatch a
  // value that JSON.stringify silently coerces to null on the wire.
  const numberParse =
    scalarType === 'long' || scalarType === 'double'
      ? parseNumberInput(numberRaw, scalarType)
      : null
  const staged: unknown =
    scalarType === 'boolean'
      ? boolValue
      : scalarType === 'string'
        ? textValue
        : numberParse?.ok === true
          ? numberParse.value
          : undefined
  const stagedValid =
    scalarType === 'boolean'
      ? boolValue !== undefined
      : scalarType === 'string'
        ? true
        : numberParse?.ok === true

  return (
    <ModalShell
      title="Rerun from activity — step 1 of 2: fix the data"
      environment={engine?.environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="primary"
            disabled={selected === undefined || full.isPending || !stagedValid}
            title={
              selected === undefined
                ? 'pick a variable to edit first'
                : !stagedValid
                  ? 'enter a value'
                  : undefined
            }
            onClick={() => {
              if (selected === undefined || originalValue === undefined) return
              onReview({
                variable: {
                  name,
                  type: scalarType,
                  value: staged,
                  expectedOldValue: originalValue,
                },
              })
            }}
          >
            Review the edit…
          </button>
        </>
      }
    >
      <p className="modal-target-heading">
        Instance <code>{`${engineId}:${instanceId}`}</code>
      </p>
      <p className="strip-note">
        A guided two-step recovery composite (issue #102): fix a case-wide value here, verify and
        apply it, then move the token past the bad step on the next screen — both halves audited
        separately, exactly as if you'd done them one after another yourself.
      </p>

      <div className="modal-field">
        <span>Variable to edit (case-wide only)</span>
        {variables.isPending && <p className="zero-state">Loading variables…</p>}
        {variables.isError && (
          <p className="strip-note">The variable list could not be loaded — close and retry.</p>
        )}
        {variables.isSuccess && caseScoped.length === 0 && (
          <p className="zero-state">No case-wide variables on this instance.</p>
        )}
        {caseScoped.length > 0 && (
          <select
            value={name}
            aria-label="variable name"
            onChange={(event) => {
              onNameChange(event.target.value)
              setTextValue('')
              setNumberRaw('')
              setBoolValue(undefined)
            }}
          >
            <option value="">— choose a variable —</option>
            {caseScoped.map((entry) => (
              <option key={entry.name} value={entry.name}>
                {entry.name} ({entry.type ?? 'unknown type'})
              </option>
            ))}
          </select>
        )}
      </div>

      {jsonValued && (
        <p className="strip-note" role="alert">
          <code>{name}</code> is a structured (json) value — this composite only edits scalar
          (text/number/yes-no) variables. Edit it from the Variables tab first, then use plain
          "Change state / move token" for the move.
        </p>
      )}

      {name !== '' && !jsonValued && full.isPending && (
        <p className="zero-state">Fetching the full current value…</p>
      )}
      {name !== '' && !jsonValued && full.isError && (
        <p className="strip-note">The full value could not be fetched — close and retry.</p>
      )}

      {name !== '' && !jsonValued && full.isSuccess && (
        <div className="modal-field">
          <span>Current value</span>
          <pre className="value-body">{renderOld(originalValue)}</pre>
          <span>New value</span>
          {scalarType === 'string' && <TextWidget value={textValue} onChange={setTextValue} />}
          {(scalarType === 'long' || scalarType === 'double') && (
            <NumberWidget raw={numberRaw} subtype={scalarType} onChange={setNumberRaw} />
          )}
          {scalarType === 'boolean' && <BooleanWidget value={boolValue} onChange={setBoolValue} />}
        </div>
      )}
    </ModalShell>
  )
}

function renderOld(value: unknown): string {
  if (value === null || value === undefined) return '(no value / null)'
  if (typeof value === 'string') return value === '' ? '(empty text)' : value
  return JSON.stringify(value, null, 2)
}
