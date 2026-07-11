// @vitest-environment jsdom
// Usability W2 #6 (theme T7): no silently dead controls — a disabled edit pencil carries
// the visible ActionHint gate (🔒 + the short reason naming the missing grant), the same
// pattern every sibling action button already wears. Title-only hover hints lose.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LedgerGroup } from './ledger'
import { VariableLedger } from './VariableLedger'

afterEach(cleanup)

const group: LedgerGroup = {
  label: 'Case variables',
  scope: 'process',
  rows: [
    {
      entry: { name: 'amount', value: 42, scope: 'process', engineType: 'integer' },
      chip: 'number',
      preview: { text: '42', muted: false, expandable: false },
      shadowsProcessScope: false,
    },
  ],
}

describe('VariableLedger edit-pencil gate hint (W2 #6, T7)', () => {
  it('a gated pencil is disabled AND wears the visible ActionHint naming the grant', () => {
    render(
      <VariableLedger
        groups={[group]}
        editGateReason={() => 'Requires OPERATOR — you are RESPONDER'}
        onEditRow={vi.fn()}
      />,
    )
    const pencil = screen.getByRole('button', { name: /edit amount/ })
    expect(pencil.getAttribute('disabled')).not.toBeNull()
    // The gate is VISIBLE (role=note), not buried in the hover title.
    const hint = screen.getByText(/Requires OPERATOR — you are RESPONDER/)
    expect(hint.closest('[role="note"]')).not.toBeNull()
    // And the pencil points at it for AT users.
    expect(pencil.getAttribute('aria-describedby')).toBe(hint.closest('small')?.id)
  })

  it('an editable pencil renders no gate hint', () => {
    render(<VariableLedger groups={[group]} editGateReason={() => null} onEditRow={vi.fn()} />)
    const pencil = screen.getByRole('button', { name: /edit amount/ })
    expect(pencil.getAttribute('disabled')).toBeNull()
    expect(screen.queryByRole('note')).toBeNull()
  })
})
