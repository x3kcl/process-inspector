import { useEffect, useRef, useState } from 'react'
import { shortcutChord } from './shortcuts'

const OMNIBOX_ID = 'omnibox'

export function useShellShortcuts(): { sheetOpen: boolean; closeSheet: () => void } {
  const [sheetOpen, setSheetOpen] = useState(false)
  const armed = useRef(false)
  const previous = useRef<HTMLElement | null>(null)

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const omniboxFocused = armed.current && document.activeElement?.id === OMNIBOX_ID
      const chord = shortcutChord(event, event.target, omniboxFocused, sheetOpen)
      if (chord === null) return
      const omnibox = document.getElementById(OMNIBOX_ID)
      if (chord === 'focus-omnibox') {
        if (!(omnibox instanceof HTMLInputElement)) return
        event.preventDefault()
        const active = document.activeElement
        if (active !== omnibox && active instanceof HTMLElement) previous.current = active
        omnibox.focus()
        omnibox.select()
        armed.current = true
        setSheetOpen(false)
        return
      }
      if (chord === 'cheat-sheet') {
        event.preventDefault()
        setSheetOpen(true)
        return
      }
      event.preventDefault()
      armed.current = false
      const back = previous.current
      previous.current = null
      if (back !== null && back.isConnected) back.focus()
      else document.getElementById('main-content')?.focus()
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
    }
  }, [sheetOpen])

  return {
    sheetOpen,
    closeSheet: () => {
      setSheetOpen(false)
    },
  }
}
