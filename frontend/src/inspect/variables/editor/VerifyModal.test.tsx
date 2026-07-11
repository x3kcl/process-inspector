// @vitest-environment jsdom
// Usability W2 #1 (theme T10, SPEC §10a): the edit-variable verify dialog must never
// refuse silently. It adopts the bulk dialog's inline gate copy ("Reason too short —
// 10+ characters") next to the disabled confirm button, plus aria-invalid on the reason
// field — converging the two behaviors of the same ≥10 rule.
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EngineDto } from '../../../api/model'
import { VerifyModal } from './VerifyModal'

vi.mock('../../../api/queries', () => ({
  // Freshness re-check resolves to the same value the editor started from → 'fresh',
  // so the ONLY gate left on the confirm button is the reason rule under test.
  fetchInstanceVariable: vi.fn().mockResolvedValue({ value: 'old' }),
}))

afterEach(cleanup)

const prodEngine: EngineDto = { id: 'engine-p', name: 'Engine P', environment: 'prod' }

function renderModal() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <VerifyModal
        engineId="engine-p"
        instanceId="pi-1"
        engine={prodEngine}
        targetLabel="ORDER-77"
        request={{ variable: { name: 'amount', value: 'new', expectedOldValue: 'old' } }}
        typeLabel="text"
        typeChanged={null}
        pending={false}
        onDispatch={vi.fn()}
        onStartOver={vi.fn()}
        onClose={vi.fn()}
      />
    </QueryClientProvider>,
  )
}

describe('VerifyModal reason gate feedback (W2 #1, T10)', () => {
  it('shows the bulk dialog’s inline gate copy while the reason is too short — never a silent disable', async () => {
    renderModal()
    await waitFor(() => screen.getByText(/re-checked — unchanged/))

    // Empty reason on PROD (required): visible inline gate, not just a disabled button.
    expect(screen.getByText(/Reason too short — 10\+ characters/)).toBeTruthy()
    const reasonField = screen.getByRole('textbox')
    expect(reasonField.getAttribute('aria-invalid')).toBe('true')

    fireEvent.change(reasonField, { target: { value: 'short' } })
    expect(screen.getByText(/Reason too short — 10\+ characters/)).toBeTruthy()

    fireEvent.change(reasonField, { target: { value: 'a long enough reason' } })
    expect(screen.queryByText(/Reason too short — 10\+ characters/)).toBeNull()
    expect(reasonField.getAttribute('aria-invalid')).toBe('false')
  })

  it('the disabled confirm button points at the visible hint via aria-describedby', async () => {
    renderModal()
    await waitFor(() => screen.getByText(/re-checked — unchanged/))

    const confirm = screen.getByRole('button', { name: /Change amount/ })
    expect(confirm.getAttribute('disabled')).not.toBeNull()
    const describedBy = confirm.getAttribute('aria-describedby')
    expect(describedBy).toBeTruthy()
    const hint = document.getElementById(describedBy ?? '')
    expect(hint?.textContent).toContain('Reason too short — 10+ characters')
  })
})
