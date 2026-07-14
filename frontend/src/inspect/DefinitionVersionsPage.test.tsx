// @vitest-environment jsdom
// R-SAFE-05 definition-key write path (#172, deferred from #165): the protect/unprotect
// trigger on the definition-versions page — same greyed-never-hidden ADMIN-only gate as the
// instance-level trigger in InstanceActions.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DefinitionVersionsResponse } from '../api/migrate'

vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'admin', role: 'ADMIN' } }),
  roleOn: vi.fn(),
}))

let versionsData: DefinitionVersionsResponse | undefined
vi.mock('../api/migrate', async () => {
  const actual = await vi.importActual<typeof import('../api/migrate')>('../api/migrate')
  return {
    ...actual,
    useDefinitionVersions: () => ({
      isPending: versionsData === undefined,
      isError: false,
      isSuccess: versionsData !== undefined,
      data: versionsData,
    }),
  }
})

const protectMutate = vi.fn()
const unprotectMutate = vi.fn()
vi.mock('../api/protection', () => ({
  useProtectDefinition: () => ({ mutate: protectMutate, isPending: false, error: undefined }),
  useUnprotectDefinition: () => ({ mutate: unprotectMutate, isPending: false, error: undefined }),
}))

import { roleOn } from '../api/me'
import { DefinitionVersionsPage } from './DefinitionVersionsPage'

afterEach(() => {
  cleanup()
  protectMutate.mockClear()
  unprotectMutate.mockClear()
  versionsData = undefined
})

const UNPROTECTED: DefinitionVersionsResponse = {
  engineId: 'engine-a',
  key: 'payment',
  latestVersion: 3,
  totalVersions: 3,
  complete: true,
  versions: [],
  protectedDefinition: false,
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/definitions/engine-a/payment/versions']}>
      <Routes>
        <Route path="/definitions/:engineId/:key/versions" element={<DefinitionVersionsPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('DefinitionVersionsPage — protect/unprotect trigger (#172, ADMIN only)', () => {
  it('an ADMIN sees "Protect this definition…" on an unprotected key', () => {
    vi.mocked(roleOn).mockReturnValue('ADMIN')
    versionsData = UNPROTECTED
    renderPage()
    expect(screen.getByRole('button', { name: /🔒 Protect this definition…/ })).toBeTruthy()
    expect(screen.queryByText('🔒 Protected')).toBeNull()
  })

  it('an ADMIN sees "Unprotect this definition…" and the badge on an already-protected key', () => {
    vi.mocked(roleOn).mockReturnValue('ADMIN')
    versionsData = {
      ...UNPROTECTED,
      protectedDefinition: true,
      protectionReason: 'v3 rollout freeze',
    }
    renderPage()
    expect(screen.getByRole('button', { name: /^Unprotect this definition…/ })).toBeTruthy()
    expect(screen.getByText('🔒 Protected')).toBeTruthy()
  })

  it('a non-ADMIN sees the trigger disabled with a hint, never hidden', () => {
    vi.mocked(roleOn).mockReturnValue('OPERATOR')
    versionsData = UNPROTECTED
    renderPage()
    const button = screen.getByRole('button', { name: /🔒 Protect this definition…/ })
    expect(button).toHaveProperty('disabled', true)
    expect(screen.getByText(/needs ADMIN on this engine/)).toBeTruthy()
  })

  it('submitting protect calls the mutation with the trimmed reason', async () => {
    vi.mocked(roleOn).mockReturnValue('ADMIN')
    versionsData = UNPROTECTED
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: /🔒 Protect this definition…/ }))
    fireEvent.change(screen.getByLabelText(/reason/i), {
      target: { value: '  v3 rollout freeze pending review  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^Protect payment$/ }))
    await waitFor(() => {
      expect(protectMutate).toHaveBeenCalledWith(
        { reason: 'v3 rollout freeze pending review' },
        expect.anything(),
      )
    })
    expect(unprotectMutate).not.toHaveBeenCalled()
  })
})
