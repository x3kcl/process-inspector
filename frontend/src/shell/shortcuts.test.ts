// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { SHORTCUTS, isEditableTarget, shortcutChord } from './shortcuts'

const key = (over: Partial<KeyboardEvent> & { key: string }) => ({
  metaKey: false,
  ctrlKey: false,
  altKey: false,
  repeat: false,
  ...over,
})

describe('shortcutChord', () => {
  it('focuses the omnibox on Ctrl+K and ⌘K even from a field', () => {
    const input = document.createElement('input')
    expect(shortcutChord(key({ key: 'k', ctrlKey: true }), input, false, false)).toBe(
      'focus-omnibox',
    )
    expect(shortcutChord(key({ key: 'k', metaKey: true }), input, false, false)).toBe(
      'focus-omnibox',
    )
  })

  it('opens the sheet on ? outside a field and ignores it inside one', () => {
    const input = document.createElement('input')
    expect(shortcutChord(key({ key: '?' }), document.body, false, false)).toBe('cheat-sheet')
    expect(shortcutChord(key({ key: '?' }), input, false, false)).toBeNull()
    expect(isEditableTarget(input)).toBe(true)
  })

  it('restores focus on Esc only when the omnibox chord armed it', () => {
    expect(shortcutChord(key({ key: 'Escape' }), document.body, true, false)).toBe('restore-focus')
    expect(shortcutChord(key({ key: 'Escape' }), document.body, false, false)).toBeNull()
  })

  it('lets an open dialog own Esc and ?', () => {
    expect(shortcutChord(key({ key: 'Escape' }), document.body, true, true)).toBeNull()
    expect(shortcutChord(key({ key: '?' }), document.body, false, true)).toBeNull()
    expect(shortcutChord(key({ key: 'k', ctrlKey: true }), document.body, false, true)).toBe(
      'focus-omnibox',
    )
  })

  it('lists every chord the handler implements', () => {
    const ids = SHORTCUTS.map((binding) => binding.id)
    expect(ids).toContain('focus-omnibox')
    expect(ids).toContain('cheat-sheet')
    expect(ids).toContain('restore-focus')
  })
})
