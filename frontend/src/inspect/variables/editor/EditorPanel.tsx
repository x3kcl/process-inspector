// The §4a inline edit panel — under the row, never a modal, ledger and vitals stay
// visible. Opening FORCES the full-value fetch (a truncated projection is never
// editable); the old value stays visible throughout; Form is the default mode and the
// CodeMirror source chunk loads lazily, json variables only. One verification screen
// (VerifyModal) renders the change-set; dispatch is compare-and-set with no optimistic
// UI — the ledger re-renders from re-fetched server truth via the mutation's
// invalidation.
import { Suspense, lazy, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { dispatchInstanceAction, useInstanceAction } from '../../../api/actions'
import type { ActionRequest } from '../../../api/actions'
import { ActionError } from '../../../api/actions'
import type { EngineDto, InstanceDetail } from '../../../api/model'
import { fetchInstanceJobs, fetchInstanceVariable } from '../../../api/queries'
import { problemBanner } from '../../../actions/problem'
import { EnvBadge } from '../../../components/EnvBadge'
import { Segmented } from '../../../components/Segmented'
import { useToast } from '../../../components/toast'
import type { VariableEntry } from '../ledger'
import { BooleanWidget, DateWidget, LeafTree, NumberWidget, TextWidget } from './FormMode'
import { VerifyModal } from './VerifyModal'
import {
  EDITABLE_TYPES,
  SOURCE_BLOCK_BYTES,
  applyLeafEdits,
  asEditableType,
  checkSourceBuffer,
  clearedValue,
  formatPath,
  parseDateInput,
  parseNumberInput,
} from './editState'
import type { ClearChoice, EditableType, LeafPath } from './editState'
import { serializedBytes } from '../ledger'

// The source editor is its own chunk (§4a: never bundled eagerly).
const SourceEditor = lazy(() => import('./SourceEditor'))

interface PanelProps {
  engineId: string
  instanceId: string
  entry: VariableEntry
  engine?: EngineDto
  vitals?: InstanceDetail
  onClose: () => void
}

export function EditorPanel({ engineId, instanceId, entry, engine, vitals, onClose }: PanelProps) {
  // §4a Entry: the full-value fetch is unconditional — the ledger's projection may be
  // truncated or stale, and an edit staged over either would be a lie.
  const full = useQuery({
    queryKey: ['edit-variable-full', engineId, instanceId, entry.name],
    queryFn: () => fetchInstanceVariable({ engineId, instanceId }, entry.name),
    staleTime: 0,
    gcTime: 0,
    refetchOnMount: 'always',
  })
  // Start-over (CAS conflict / staleness) re-seeds the session from the engine value.
  const [reseed, setReseed] = useState<{ value: unknown; generation: number } | null>(null)

  if (full.isPending) {
    return (
      <div className="editor-panel">
        <p className="zero-state">Fetching the full current value…</p>
      </div>
    )
  }
  if (full.isError) {
    return (
      <div className="editor-panel">
        <div className="error-banner" role="alert">
          The full value could not be fetched — editing stays locked: {full.error.message}
        </div>
        <button type="button" className="copy-btn" onClick={onClose}>
          close
        </button>
      </div>
    )
  }

  const originalValue = reseed !== null ? reseed.value : (full.data.value ?? null)
  const declaredType = full.data.type ?? entry.engineType

  return (
    <EditorSession
      // Remount on reseed: a fresh session over the new base value, everything staged
      // before is deliberately discarded (start over, not merge).
      key={reseed?.generation ?? 0}
      engineId={engineId}
      instanceId={instanceId}
      entry={entry}
      engine={engine}
      vitals={vitals}
      originalValue={originalValue}
      declaredType={declaredType}
      onStartOver={(value) => {
        setReseed((previous) => ({ value, generation: (previous?.generation ?? 0) + 1 }))
      }}
      onClose={onClose}
    />
  )
}

function inferType(value: unknown): EditableType {
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'number') return Number.isInteger(value) ? 'long' : 'double'
  if (value !== null && typeof value === 'object') return 'json'
  return 'string'
}

const TYPE_LABELS: Record<EditableType, string> = {
  string: 'text',
  integer: 'number',
  long: 'number',
  short: 'number',
  double: 'number',
  boolean: 'yes-no',
  date: 'date',
  json: 'structured (json)',
}

function EditorSession({
  engineId,
  instanceId,
  entry,
  engine,
  vitals,
  originalValue,
  declaredType,
  onStartOver,
  onClose,
}: {
  engineId: string
  instanceId: string
  entry: VariableEntry
  engine?: EngineDto
  vitals?: InstanceDetail
  originalValue: unknown
  declaredType?: string
  onStartOver: (value: unknown) => void
  onClose: () => void
}) {
  const toast = useToast()
  const action = useInstanceAction(engineId, instanceId)
  const queryClient = useQueryClient()
  const lockedType: EditableType = asEditableType(declaredType) ?? inferType(originalValue)

  // Type lock (§4a): changing the base type needs the explicit per-session unlock.
  const [typeUnlocked, setTypeUnlocked] = useState(false)
  const [chosenType, setChosenType] = useState<EditableType>(lockedType)

  // Scalar widget state, seeded from the full-fetched original.
  const [textValue, setTextValue] = useState(() =>
    typeof originalValue === 'string' ? originalValue : '',
  )
  const [numberRaw, setNumberRaw] = useState(() =>
    typeof originalValue === 'number' ? String(originalValue) : '',
  )
  const [boolValue, setBoolValue] = useState<boolean | undefined>(() =>
    typeof originalValue === 'boolean' ? originalValue : undefined,
  )
  const [dateRaw, setDateRaw] = useState(() =>
    typeof originalValue === 'string' ? originalValue : '',
  )
  // JSON: the staged document (base + leaf edits applied) plus which paths moved.
  const [stagedDoc, setStagedDoc] = useState<unknown>(originalValue)
  const [editedPaths, setEditedPaths] = useState<Set<string>>(new Set())
  const [mode, setMode] = useState<'form' | 'source'>('form')
  const [sourceBuffer, setSourceBuffer] = useState('')
  // Clearing is an explicit choice (§4a), never inferred from an emptied input.
  const [cleared, setCleared] = useState<ClearChoice | null>(null)
  const [verifyOpen, setVerifyOpen] = useState(false)

  const sourceCheck = useMemo(
    () => (mode === 'source' ? checkSourceBuffer(sourceBuffer) : null),
    [mode, sourceBuffer],
  )

  const staged = stagedValue()
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`

  function stagedValue(): { ok: true; value: unknown } | { ok: false; error: string } {
    if (cleared !== null) return { ok: true, value: clearedValue(cleared) }
    switch (chosenType) {
      case 'string':
        return { ok: true, value: textValue }
      case 'boolean':
        return boolValue === undefined
          ? { ok: false, error: 'choose True or False' }
          : { ok: true, value: boolValue }
      case 'date': {
        const parsed = parseDateInput(dateRaw)
        return parsed.ok ? { ok: true, value: parsed.value } : parsed
      }
      case 'integer':
      case 'long':
      case 'short':
      case 'double': {
        const parsed = parseNumberInput(numberRaw, chosenType)
        return parsed.ok ? { ok: true, value: parsed.value } : parsed
      }
      case 'json': {
        if (mode === 'source') {
          if (sourceCheck === null || !sourceCheck.ok) {
            return { ok: false, error: sourceCheck?.error ?? 'invalid source buffer' }
          }
          return { ok: true, value: sourceCheck.value }
        }
        if (serializedBytes(stagedDoc) > SOURCE_BLOCK_BYTES) {
          return { ok: false, error: 'the value exceeds the 5 MiB write cap' }
        }
        return { ok: true, value: stagedDoc }
      }
    }
  }

  const unchanged =
    staged.ok && JSON.stringify(staged.value) === JSON.stringify(originalValue ?? null)

  const request: ActionRequest | null = staged.ok
    ? {
        variable: {
          name: entry.name,
          type: chosenType,
          value: staged.value,
          expectedOldValue: originalValue,
        },
      }
    : null

  const switchMode = (next: 'form' | 'source') => {
    if (next === mode) return
    if (next === 'source') {
      // Form→Source serializes the staged state losslessly (§4a).
      setSourceBuffer(JSON.stringify(stagedDoc, null, 2))
      setMode('source')
      return
    }
    // Source→Form is BLOCKED while the buffer is invalid — the form never lies.
    if (sourceCheck === null || !sourceCheck.ok) return
    setStagedDoc(sourceCheck.value)
    setEditedPaths(new Set())
    setMode('form')
  }

  const onLeafChange = (path: LeafPath, value: unknown) => {
    setStagedDoc((current: unknown) => applyLeafEdits(current, [{ path, value }]))
    setEditedPaths((current) => new Set(current).add(formatPath(path)))
  }

  const dispatch = (reason: string | undefined) => {
    if (request === null) return
    action.mutate(
      { verb: 'edit-variable', body: { ...request, reason } },
      {
        onSuccess: (result) => {
          toast({
            kind: 'success',
            text: result.deltaStatement ?? `${entry.name} updated`,
            auditPath,
          })
          onClose()
          offerFollowOnRetry()
        },
        // Failures render inside the verify modal (CAS gets its replacement panel).
      },
    )
  }

  /**
   * §4a/§5.1 follow-on — the #1 incident sequence is edit-the-poisoned-variable THEN
   * retry the dead-letter job. OFFERED, never automatic: a sticky toast with an explicit
   * button, only when the instance is actually FAILED with a dead-letter job right now.
   */
  const offerFollowOnRetry = () => {
    if (vitals?.status !== 'FAILED') return
    fetchInstanceJobs({ engineId, instanceId })
      .then((jobs) => {
        const deadLetter = jobs.deadLetter ?? []
        const jobId = deadLetter[0]?.id
        if (jobId === undefined) return
        toast({
          kind: 'success',
          text:
            deadLetter.length === 1
              ? `This case is still FAILED — job ${jobId} is dead-lettered.`
              : `This case is still FAILED with ${String(deadLetter.length)} dead-letter jobs.`,
          sticky: true,
          action: {
            label: `Retry the failed job?`,
            run: () => {
              dispatchInstanceAction(engineId, instanceId, 'retry-job', { jobId })
                .then(async (result) => {
                  toast({
                    kind: 'success',
                    text: result.deltaStatement ?? `job ${jobId} moved back to the executable queue`,
                    auditPath,
                  })
                  await queryClient.invalidateQueries({ queryKey: ['instance', engineId, instanceId] })
                  await queryClient.invalidateQueries({ queryKey: ['audit', engineId, instanceId] })
                })
                .catch((error: unknown) => {
                  toast({
                    kind: 'error',
                    text:
                      error instanceof ActionError
                        ? problemBanner(error.problem)
                        : String(error),
                  })
                })
            },
          },
        })
      })
      .catch(() => {
        // The offer is best-effort decoration; a failed jobs read stays silent.
      })
  }

  const businessKey = vitals?.businessKey
  const targetLabel = businessKey !== undefined && businessKey !== '' ? businessKey : instanceId
  const jsonType = chosenType === 'json'

  return (
    <div className="editor-panel">
      {/* Panel header restates the target — the wrong-instance error dies here (§4a). */}
      <div className="editor-target">
        <EnvBadge environment={engine?.environment} accentColor={engine?.accentColor} />
        <span className="engine-name">{engine?.name ?? engineId}</span>
        {businessKey !== undefined && businessKey !== '' && <code>{businessKey}</code>}
        <code className="composite-id">{`${engineId}:${instanceId}`}</code>
        <span>
          editing <code>{entry.name}</code>{' '}
          <span className="value-muted">(case scope · engine type {declaredType ?? 'undeclared'})</span>
        </span>
      </div>

      {/* The old value stays visible above the input throughout (§4a). */}
      <div className="editor-old-value">
        <span className="value-pane-label">Current value</span>
        <pre className="value-body">{renderOld(originalValue)}</pre>
      </div>

      <div className="editor-controls">
        <label className="type-lock">
          <input
            type="checkbox"
            checked={typeUnlocked}
            onChange={(event) => {
              const next = event.target.checked
              setTypeUnlocked(next)
              if (!next) setChosenType(lockedType)
            }}
          />
          unlock type — downstream gateways/scripts may depend on this type; text “42” and
          number 42 behave differently
        </label>
        {typeUnlocked && (
          <select
            className="type-select"
            value={chosenType}
            aria-label="new engine type"
            onChange={(event) => {
              setChosenType(event.target.value as EditableType)
              setCleared(null)
            }}
          >
            {EDITABLE_TYPES.map((candidate) => (
              <option key={candidate} value={candidate}>
                {candidate}
              </option>
            ))}
          </select>
        )}
        {jsonType && (
          <Segmented<'form' | 'source'>
            ariaLabel="editor mode"
            options={[
              { value: 'form', label: 'Form' },
              {
                value: 'source',
                label: 'Source',
                disabled: mode === 'source' ? false : cleared !== null,
                title:
                  mode === 'source' && sourceCheck !== null && !sourceCheck.ok
                    ? 'fix the JSON to return to Form mode'
                    : undefined,
              },
            ]}
            value={mode}
            onChange={switchMode}
          />
        )}
      </div>

      {cleared !== null ? (
        <div className="editor-cleared">
          will be cleared to{' '}
          <strong>{cleared === 'empty-text' ? 'empty text ("")' : 'no value (null)'}</strong>
          <button
            type="button"
            className="copy-btn"
            onClick={() => {
              setCleared(null)
            }}
          >
            undo clear
          </button>
        </div>
      ) : (
        <div className="editor-widget-slot">
          {chosenType === 'string' && <TextWidget value={textValue} onChange={setTextValue} />}
          {(chosenType === 'integer' ||
            chosenType === 'long' ||
            chosenType === 'short' ||
            chosenType === 'double') && (
            <NumberWidget raw={numberRaw} subtype={chosenType} onChange={setNumberRaw} />
          )}
          {chosenType === 'boolean' && <BooleanWidget value={boolValue} onChange={setBoolValue} />}
          {chosenType === 'date' && <DateWidget raw={dateRaw} onChange={setDateRaw} />}
          {jsonType && mode === 'form' && (
            <LeafTree value={stagedDoc} editedPaths={editedPaths} onLeafChange={onLeafChange} />
          )}
          {jsonType && mode === 'source' && (
            <Suspense fallback={<p className="zero-state">Loading source editor…</p>}>
              <SourceEditor initialValue={sourceBuffer} onChange={setSourceBuffer} />
            </Suspense>
          )}
          {jsonType && mode === 'source' && sourceCheck !== null && !sourceCheck.ok && (
            <p className="edit-error" role="alert">
              {sourceCheck.error}
            </p>
          )}
          {jsonType && mode === 'source' && sourceCheck?.ok === true && sourceCheck.warning !== undefined && (
            <p className="edit-echo">⚠ {sourceCheck.warning}</p>
          )}
          {jsonType && mode === 'source' && (
            <p className="value-muted source-note">
              saving re-serializes — the review compares values, not formatting
            </p>
          )}
        </div>
      )}

      <div className="editor-actions">
        {!jsonType && cleared === null && (
          <span className="clear-group">
            clear to:
            <button
              type="button"
              className="copy-btn"
              onClick={() => {
                setCleared('empty-text')
              }}
            >
              empty text
            </button>
            <button
              type="button"
              className="copy-btn"
              onClick={() => {
                setCleared('null')
              }}
            >
              no value (null)
            </button>
          </span>
        )}
        <button type="button" className="copy-btn" onClick={onClose}>
          cancel
        </button>
        <button
          type="button"
          className="primary review-btn"
          disabled={!staged.ok || unchanged || request === null}
          title={
            !staged.ok ? staged.error : unchanged ? 'nothing changed yet' : undefined
          }
          onClick={() => {
            action.reset()
            setVerifyOpen(true)
          }}
        >
          Review change…
        </button>
      </div>

      {action.error !== null && !verifyOpen && (
        <div className="error-banner" role="alert">
          {problemBanner(action.error.problem)}
        </div>
      )}

      {verifyOpen && request !== null && (
        <VerifyModal
          engineId={engineId}
          instanceId={instanceId}
          engine={engine}
          targetLabel={targetLabel}
          request={request}
          typeLabel={TYPE_LABELS[chosenType]}
          typeChanged={chosenType !== lockedType ? { from: lockedType, to: chosenType } : null}
          pending={action.isPending}
          problem={action.error?.problem}
          onDispatch={dispatch}
          onStartOver={(currentValue) => {
            setVerifyOpen(false)
            onStartOver(currentValue)
          }}
          onClose={() => {
            setVerifyOpen(false)
          }}
        />
      )}
    </div>
  )
}

function renderOld(value: unknown): string {
  if (value === null || value === undefined) return '(no value / null)'
  if (typeof value === 'string') return value === '' ? '(empty text)' : value
  return JSON.stringify(value, null, 2)
}
