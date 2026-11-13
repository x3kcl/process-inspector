// Historic migration of ONE ended case (#500): re-points its history to another version of the
// same case key. The engine route exists on Flowable 7.x only — on an older engine the control
// is greyed with the reason the BFF would refuse with (version gate), never hidden.
import { useState } from 'react'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { useCaseDefinitionVersions, useHistoricCaseMigrate } from '../api/caseMigration'
import { roleOn, useMe } from '../api/me'
import { useEngines } from '../api/useEngines'
import { GuardFields } from '../components/GuardFields'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'

interface Props {
  engineId: string
  caseInstanceId: string
  caseDefinitionKey: string
  caseDefinitionId?: string
}

const GATE_HINT = 'historic-case-gate'

export function CaseHistoricMigrate({
  engineId,
  caseInstanceId,
  caseDefinitionKey,
  caseDefinitionId,
}: Props) {
  const versions = useCaseDefinitionVersions(engineId, caseDefinitionKey)
  const engines = useEngines()
  const me = useMe()
  const toast = useToast()
  const migrate = useHistoricCaseMigrate(engineId, caseInstanceId)
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment
  const admin = roleOn(me.data, engineId) === 'ADMIN'
  const gate = versions.data?.definitionRoutesGate ?? undefined
  const targets = (versions.data?.versions ?? []).filter(
    (version) => version.caseDefinitionId !== caseDefinitionId,
  )
  const [picked, setPicked] = useState('')
  const toCaseDefinitionId =
    picked !== ''
      ? picked
      : (targets.find((v) => v.latest)?.caseDefinitionId ?? targets[0]?.caseDefinitionId ?? '')
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken: caseInstanceId,
  })

  const submit = () => {
    migrate.mutate(
      {
        toCaseDefinitionId,
        reason: guard.reason.trim(),
        ticketId: ticketValue(guard.ticket),
        confirmToken: guard.needsToken ? guard.typed : undefined,
      },
      {
        onSuccess: (result) => {
          toast({ kind: 'success', text: result.deltaStatement ?? 'History migrated.' })
        },
      },
    )
  }

  return (
    <section aria-label="Migrate history">
      <h3>Migrate history</h3>
      <p>
        This case has ended. Its history can be re-pointed to another version of{' '}
        <code>{caseDefinitionKey}</code>; nothing else about the case changes.
      </p>
      {gate !== undefined && (
        <p id={GATE_HINT} className="strip-note" role="note">
          Historic case migration {gate}.
        </p>
      )}
      <label className="modal-field">
        Target version
        <select
          aria-label="Target version"
          value={toCaseDefinitionId}
          disabled={gate !== undefined}
          onChange={(event) => {
            setPicked(event.target.value)
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
        expectedToken={caseInstanceId}
        tokenFieldLabel={
          <>
            Type the case id <code>{caseInstanceId}</code> to confirm
          </>
        }
      />
      <TicketField value={guard.ticket} onChange={guard.setTicket} />
      <button
        type="button"
        disabled={
          gate !== undefined ||
          !admin ||
          toCaseDefinitionId === '' ||
          !guard.reasonOk ||
          !guard.tokenOk ||
          migrate.isPending
        }
        aria-describedby={gate !== undefined ? GATE_HINT : undefined}
        title={gate ?? (admin ? undefined : 'Needs ADMIN on this engine.')}
        onClick={submit}
      >
        Migrate history
      </button>
      {migrate.isError && <p role="alert">{problemBanner(migrate.error.problem)}</p>}
    </section>
  )
}
