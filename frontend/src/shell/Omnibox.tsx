import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useEngines } from '../api/useEngines'
import { encodeSearch } from '../search/urlState'
import { classifyOmniboxInput } from './omnibox'

/**
 * The global "paste anything" input, pinned in the header on every stage (SPEC §4).
 * engine:id composites open Stage 2 directly; everything else becomes a pre-filtered
 * business-key search. Raw engine-ID resolution arrives with GET /api/resolve.
 */
export function Omnibox() {
  const navigate = useNavigate()
  const engines = useEngines()
  const [value, setValue] = useState('')

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const engineIds = (engines.data ?? [])
      .map((engine) => engine.id)
      .filter((id): id is string => id !== undefined)
    const target = classifyOmniboxInput(value, engineIds)
    if (target === null) return
    if (target.kind === 'inspect') {
      void navigate(`/inspect/${target.engineId}/${encodeURIComponent(target.instanceId)}`)
    } else {
      void navigate(`/search?${encodeSearch({ businessKey: target.businessKey }).toString()}`)
    }
    setValue('')
  }

  return (
    <form className="omnibox" onSubmit={submit} role="search">
      <input
        value={value}
        onChange={(event) => {
          setValue(event.target.value)
        }}
        placeholder="engine:id or business key…"
        aria-label="Open an instance by composite ID, or search a business key"
        title={
          'Paste a composite "engine:id" to open the instance, or a business key to search. ' +
          'Raw process/task/job IDs resolve once GET /api/resolve lands.'
        }
      />
    </form>
  )
}
