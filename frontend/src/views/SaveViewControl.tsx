import { useState } from 'react'
import type { FormEvent } from 'react'
import { useSavedViews } from './useViewStores'

/**
 * "Save current view" (Stage 1 rail): names the CURRENT URL search state, nothing else —
 * saving is a pure localStorage write, the search itself is untouched. Hidden while the
 * URL carries no search. Saving under an existing name replaces that view (the button
 * says so before it happens).
 */
export function SaveViewControl({ search }: { search: string | null }) {
  const { views, save } = useSavedViews()
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  if (search === null || search === '') return null

  if (!naming) {
    return (
      <button
        type="button"
        className="save-view"
        onClick={() => {
          setNaming(true)
        }}
      >
        Save current view…
      </button>
    )
  }

  const trimmed = name.trim()
  const replaces = views.some((view) => view.name === trimmed)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (trimmed === '') return
    save(trimmed, search)
    setNaming(false)
    setName('')
  }
  return (
    <form className="save-view-form" onSubmit={submit}>
      <input
        aria-label="view name"
        placeholder="View name, e.g. My stuck tax orders"
        value={name}
        autoFocus
        onChange={(event) => {
          setName(event.target.value)
        }}
      />
      <button type="submit" disabled={trimmed === ''}>
        {replaces ? 'Replace' : 'Save'}
      </button>
      <button
        type="button"
        onClick={() => {
          setNaming(false)
          setName('')
        }}
      >
        Cancel
      </button>
    </form>
  )
}
