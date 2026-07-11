// @vitest-environment jsdom
// Usability W2 #3 (theme T11, R-SAFE-16): team-view unpublish is a moderation verb — it
// yanks a shared entry point from the whole team, so the ✕ collects a REQUIRED reason
// (≥10, the shared inline gate copy) and sends it to the audited unpublish endpoint.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TeamViewDto } from '../api/model'
import { TeamViewsGroup } from './TeamViewsGroup'

const unpublish = vi.fn().mockResolvedValue(undefined)

const view: TeamViewDto = {
  id: 7,
  name: 'Stuck payments',
  search: 'status=FAILED',
  scopeEngineId: '*',
  scopeTenantId: '*',
  author: 'alice',
  createdAt: '2026-07-09T09:00:00Z',
  updatedAt: '2026-07-09T09:00:00Z',
}

vi.mock('./useTeamViews', () => ({
  useTeamViews: () => ({ views: [view], publish: vi.fn(), unpublish }),
}))
vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'alice', role: 'OPERATOR' } }),
}))

afterEach(() => {
  cleanup()
  unpublish.mockClear()
})

function renderGroup() {
  render(
    <MemoryRouter>
      <TeamViewsGroup />
    </MemoryRouter>,
  )
}

describe('TeamViewsGroup unpublish reason (W2 #3, T11)', () => {
  it('✕ opens a reason dialog instead of firing silently', () => {
    renderGroup()
    fireEvent.click(screen.getByRole('button', { name: /unpublish team view Stuck payments/ }))
    expect(unpublish).not.toHaveBeenCalled()
    expect(screen.getByText(/recorded in the operations log/i)).toBeTruthy()
  })

  it('the confirm gates on a ≥10 reason with the shared inline copy, then sends it', () => {
    renderGroup()
    fireEvent.click(screen.getByRole('button', { name: /unpublish team view Stuck payments/ }))

    const confirm = screen.getByRole('button', { name: /^Unpublish/ })
    expect(confirm.getAttribute('disabled')).not.toBeNull()
    expect(screen.getByText(/Reason too short — 10\+ characters/)).toBeTruthy()

    const reasonField = screen.getByRole('textbox')
    expect(reasonField.getAttribute('aria-invalid')).toBe('true')
    fireEvent.change(reasonField, { target: { value: 'superseded by the new canon' } })
    expect(screen.queryByText(/Reason too short — 10\+ characters/)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /^Unpublish/ }))
    expect(unpublish).toHaveBeenCalledWith(7, 'superseded by the new canon')
  })
})
