// @vitest-environment jsdom
// R-SAFE-05 write path (#165): only an ADMIN on the engine sees the Protect/Unprotect trigger;
// the modal enforces the reason floor before the mutation can fire.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InstanceDetail } from '../api/model'

vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'admin', role: 'ADMIN' } }),
  roleOn: () => 'ADMIN',
}))
vi.mock('../api/actions', () => ({
  useInstanceAction: () => ({
    mutate: vi.fn(),
    isPending: false,
    reset: vi.fn(),
    error: undefined,
  }),
}))
const protectMutate = vi.fn()
const unprotectMutate = vi.fn()
vi.mock('../api/protection', () => ({
  useProtectInstance: () => ({ mutate: protectMutate, isPending: false, error: undefined }),
  useUnprotectInstance: () => ({ mutate: unprotectMutate, isPending: false, error: undefined }),
}))

import { InstanceActions } from './InstanceActions'

afterEach(() => {
  cleanup()
  protectMutate.mockClear()
  unprotectMutate.mockClear()
})

const UNPROTECTED_VITALS: InstanceDetail = {
  compositeId: 'engine-a:pi-1',
  engineId: 'engine-a',
  processInstanceId: 'pi-1',
  businessKey: 'ORDER-1',
  status: 'ACTIVE',
  flags: { ended: false, suspended: false },
  protectedInstance: false,
}

function renderActions(vitals: InstanceDetail) {
  render(
    <MemoryRouter>
      <InstanceActions
        engineId="engine-a"
        instanceId="pi-1"
        vitals={vitals}
        engine={{ id: 'engine-a', environment: 'dev', mode: 'read-write' }}
      />
    </MemoryRouter>,
  )
}

describe('InstanceActions — protect/unprotect trigger (#165, ADMIN only)', () => {
  it('an ADMIN sees "Protect…" on an unprotected instance', () => {
    renderActions(UNPROTECTED_VITALS)
    expect(screen.getByRole('button', { name: /🔒 Protect…/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^Unprotect…/ })).toBeNull()
  })

  it('an ADMIN sees "Unprotect…" on an already-protected instance', () => {
    renderActions({
      ...UNPROTECTED_VITALS,
      protectedInstance: true,
      protectionReason: 'litigation hold',
    })
    expect(screen.getByRole('button', { name: /^Unprotect…/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /🔒 Protect…/ })).toBeNull()
  })

  it('opens the modal and the confirm button stays disabled until the reason clears the floor', () => {
    renderActions(UNPROTECTED_VITALS)
    fireEvent.click(screen.getByRole('button', { name: /🔒 Protect…/ }))
    const confirm = screen.getByRole('button', { name: /^Protect ORDER-1$/ })
    expect(confirm).toHaveProperty('disabled', true)

    const reasonField = screen.getByLabelText(/reason/i)
    fireEvent.change(reasonField, { target: { value: 'too short' } })
    expect(confirm).toHaveProperty('disabled', true)

    fireEvent.change(reasonField, { target: { value: 'regulatory hold pending review' } })
    expect(confirm).toHaveProperty('disabled', false)
  })

  it('submitting protect calls the mutation with the trimmed reason', async () => {
    renderActions(UNPROTECTED_VITALS)
    fireEvent.click(screen.getByRole('button', { name: /🔒 Protect…/ }))
    fireEvent.change(screen.getByLabelText(/reason/i), {
      target: { value: '  regulatory hold pending review  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^Protect ORDER-1$/ }))
    await waitFor(() => {
      expect(protectMutate).toHaveBeenCalledWith(
        { reason: 'regulatory hold pending review' },
        expect.anything(),
      )
    })
    expect(unprotectMutate).not.toHaveBeenCalled()
  })

  it('submitting unprotect calls the unprotect mutation, not protect', async () => {
    renderActions({
      ...UNPROTECTED_VITALS,
      protectedInstance: true,
      protectionReason: 'litigation hold',
    })
    fireEvent.click(screen.getByRole('button', { name: /^Unprotect…/ }))
    fireEvent.change(screen.getByLabelText(/reason/i), {
      target: { value: 'hold lifted, resuming' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^Unprotect ORDER-1$/ }))
    await waitFor(() => {
      expect(unprotectMutate).toHaveBeenCalledWith(
        { reason: 'hold lifted, resuming' },
        expect.anything(),
      )
    })
    expect(protectMutate).not.toHaveBeenCalled()
  })
})
