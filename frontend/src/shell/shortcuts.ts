// One table drives both the handler and the cheat sheet (#536). The sheet cannot
// list a binding the handler does not implement, or the other way around.

export interface ShortcutBinding {
  id: string
  keys: string
  when: string
  action: string
}

export const SHORTCUTS: readonly ShortcutBinding[] = [
  {
    id: 'focus-omnibox',
    keys: '⌘K / Ctrl+K',
    when: 'Anywhere, including a text field',
    action: 'Focus the omnibox and select its text',
  },
  {
    id: 'restore-focus',
    keys: 'Esc',
    when: 'Omnibox was focused by ⌘K / Ctrl+K',
    action: 'Return focus to the previous element',
  },
  {
    id: 'cheat-sheet',
    keys: '?',
    when: 'Not typing in a field',
    action: 'Open this shortcut list',
  },
  {
    id: 'grid-enter',
    keys: 'Enter',
    when: 'Results grid',
    action: 'Open the focused row',
  },
  {
    id: 'grid-arrows',
    keys: 'Arrow keys',
    when: 'Results grid',
    action: 'Move between cells',
  },
  {
    id: 'dialog-esc',
    keys: 'Esc',
    when: 'A dialog is open',
    action: 'Close the dialog',
  },
]

export type Chord = 'focus-omnibox' | 'cheat-sheet' | 'restore-focus'

export function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}

/** Classify a keydown. `omniboxArmed` is true only after this chord focused the omnibox. */
export function shortcutChord(
  event: { key: string; metaKey: boolean; ctrlKey: boolean; altKey: boolean; repeat: boolean },
  target: EventTarget | null,
  omniboxArmed: boolean,
  dialogOpen: boolean,
): Chord | null {
  if (event.repeat) return null
  const command = event.metaKey || event.ctrlKey
  if (command && !event.altKey && event.key.toLowerCase() === 'k') return 'focus-omnibox'
  if (command || event.altKey) return null
  if (dialogOpen) return null
  if (event.key === 'Escape' && omniboxArmed) return 'restore-focus'
  if (event.key === '?' && !isEditableTarget(target)) return 'cheat-sheet'
  return null
}
