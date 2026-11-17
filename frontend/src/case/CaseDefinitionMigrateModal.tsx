// The two by-definition case-migration doors that exist on Flowable 7.x only (#500):
//   batch    — every RUNNING case of one version, as an engine batch (no filter, no per-case
//              check: the BFF refuses it when the target model drops a plan-item definition);
//   historic — the history of every ENDED case of one version.
// Tier 3: reason, ticket per engine policy, and on prod the typed count of the affected cases.
// The page opens this only on a 7.x engine; on an older one the trigger is greyed with the reason.
import { useState } from 'react'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { useCaseDefinitionMigrate } from '../api/caseMigration'
import type { CaseDefinitionDoor, CaseDefinitionVersion } from '../api/caseMigration'
import { useEngines } from '../api/useEngines'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'

interface Props {
  engineId: string
  door: CaseDefinitionDoor
  from: CaseDefinitionVersion
  versions: CaseDefinitionVersion[]
  onClose: () => void
}

export function CaseDefinitionMigrateModal({ engineId, door, from, versions, onClose }: Props) {
  const engines = useEngines()
  const toast = useToast()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment
  const fromId = from.caseDefinitionId ?? ''
  const targets = versions.filter((version) => version.caseDefinitionId !== fromId)
  const [toCaseDefinitionId, setToCaseDefinitionId] = useState(
    targets.find((v) => v.latest)?.caseDefinitionId ?? targets[0]?.caseDefinitionId ?? '',
  )
  const count = door === 'batch' ? (from.runningCaseCount ?? 0) : (from.endedCaseCount ?? 0)
  const noun = door === 'batch' ? 'running' : 'ended'
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken: String(count),
  })
  const migrate = useCaseDefinitionMigrate(engineId, fromId, door)

  const submit = () => {
    migrate.mutate(
      {
        toCaseDefinitionId,
        reason: guard.reason.trim(),
        ticketId: ticketValue(guard.ticket),
        confirmedCount: guard.needsToken ? Number(guard.typed) : undefined,
      },
      {
        onSuccess: (result) => {
          toast({ kind: 'success', text: result.deltaStatement ?? 'Accepted by the engine.' })
          onClose()
        },
      },
    )
  }

  return (
    <ModalShell
      title={
        door === 'batch' ? 'Batch-migrate all running cases' : 'Migrate the history of ended cases'
      }
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
            disabled={
              toCaseDefinitionId === '' ||
              count === 0 ||
              !guard.reasonOk ||
              !guard.tokenOk ||
              migrate.isPending
            }
            onClick={submit}
          >
            {door === 'batch' ? 'Start batch' : 'Migrate history'} ({String(count)})
          </button>
        </>
      }
    >
      {door === 'batch' ? (
        <p>
          The engine moves <strong>every</strong> running case of v{String(from.version ?? '?')} (
          {String(count)} now) in one batch. There is no filter and no per-case check, so this is
          refused when the target model drops a plan-item definition of this version: use
          &quot;Migrate cases…&quot; for that. The engine answers before any case has moved; the
          batch list on this page shows the per-case outcome.
        </p>
      ) : (
        <p>
          The engine re-points the history of the {String(count)} ended case
          {count === 1 ? '' : 's'} of v{String(from.version ?? '?')} to the target version. Running
          cases are not touched.
        </p>
      )}
      <label className="modal-field">
        Target version
        <select
          aria-label="Target version"
          value={toCaseDefinitionId}
          onChange={(event) => {
            setToCaseDefinitionId(event.target.value)
          }}
        >
          {targets.map((version) => (
            <option key={version.caseDefinitionId} value={version.caseDefinitionId}>
              v{String(version.version ?? '?')}
              {version.latest === true ? ' latest' : ''}
            </option>
          ))}
        </select>
      </label>
      <GuardFields
        guard={guard}
        expectedToken={String(count)}
        tokenFieldLabel={
          <>
            Type the number of {noun} cases <code>{String(count)}</code> to confirm
          </>
        }
      />
      <TicketField value={guard.ticket} onChange={guard.setTicket} />
      {migrate.isError && <p role="alert">{problemBanner(migrate.error.problem)}</p>}
    </ModalShell>
  )
}
