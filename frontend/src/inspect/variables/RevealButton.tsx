import { useState } from 'react'
import { revealVariable, type RevealTarget } from '../../api/reveal'
import { ActionHint } from '../../components/ActionHint'
import { CopyButton } from '../../components/CopyButton'

interface Props {
  target: RevealTarget
  name: string
  executionId?: string
  /** A prod engine asks for the reason (≥10 characters) before the reveal is sent. */
  prod: boolean
  /** null = the caller may reveal; a string names the gate, shown beside the disabled button. */
  gateReason: string | null
}

const MIN_REASON = 10

/**
 * The audited reveal (#743, SPEC R-AUD-12) beside a hidden value. The value is held in THIS
 * component's state only — no query cache — so it is gone on unmount or navigation. One variable
 * per click; nothing reveals in bulk.
 */
export function RevealButton(props: Props) {
  // Keyed by what is revealed: /inspect/:engineId/:instanceId does not remount the tab when only
  // the instance changes, so a new target must start empty and a stale in-flight answer is dropped.
  const { target, name, executionId } = props
  const key = [target.kind, target.engineId, target.targetId, executionId ?? '', name].join(
    '\u0000',
  )
  return <RevealControl key={key} {...props} />
}

function RevealControl({ target, name, executionId, prod, gateReason }: Props) {
  const [revealed, setRevealed] = useState<{ value: unknown } | null>(null)
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string>()
  const hintId = `reveal-gate-${target.targetId}-${executionId ?? ''}-${name}`

  const send = (withReason?: string) => {
    setPending(true)
    setError(undefined)
    revealVariable(target, name, executionId, withReason)
      .then((result) => {
        setRevealed({ value: result.value })
        setAsking(false)
        setReason('')
      })
      .catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : String(failure))
      })
      .finally(() => {
        setPending(false)
      })
  }

  if (revealed !== null) {
    const text =
      typeof revealed.value === 'string' ? revealed.value : JSON.stringify(revealed.value ?? null)
    return (
      <span className="reveal">
        <code className="revealed-value">{text}</code> <CopyButton text={text} label="copy value" />{' '}
        <button
          type="button"
          className="copy-btn"
          onClick={() => {
            setRevealed(null)
          }}
        >
          hide
        </button>
      </span>
    )
  }

  return (
    <span className="reveal">
      <button
        type="button"
        className="copy-btn"
        aria-label={`Reveal ${name}`}
        aria-describedby={gateReason !== null ? hintId : undefined}
        disabled={gateReason !== null || pending}
        title={gateReason ?? 'show this value once — the reveal is audited'}
        onClick={() => {
          if (prod) setAsking(true)
          else send()
        }}
      >
        {pending ? 'revealing…' : 'Reveal'}
      </button>
      {gateReason !== null && <ActionHint id={hintId} text={gateReason} tone="gate" />}
      {asking && (
        <span
          role="dialog"
          aria-label={`Reveal ${name} on a prod engine`}
          className="reveal-dialog"
        >
          <label>
            Reason (prod engine, at least {MIN_REASON} characters){' '}
            <input
              value={reason}
              onChange={(event) => {
                setReason(event.target.value)
              }}
            />
          </label>{' '}
          <button
            type="button"
            className="copy-btn"
            disabled={reason.trim().length < MIN_REASON || pending}
            onClick={() => {
              send(reason.trim())
            }}
          >
            Reveal on prod
          </button>{' '}
          <button
            type="button"
            className="copy-btn"
            onClick={() => {
              setAsking(false)
            }}
          >
            Cancel
          </button>
        </span>
      )}
      {error !== undefined && (
        <span role="alert" className="error-inline">
          {error}
        </span>
      )}
    </span>
  )
}
