// @vitest-environment jsdom
// Dangerous-set freshness parity for instance migration (issue #295, IDP-SECURITY.md §5):
// MigrateModal's execute step gets the SAME /api/me pre-emptive hint + reactive 401
// challenge handling as DestructiveModal — a stale session disables "Migrate" and shows
// ReauthNotice instead of the plain error banner, and preview() is never gated by either
// signal (mirrors the BFF's MigrationService.execute()-only reauth.enforce() placement).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InstanceDetail } from '../api/model'
import type { DefinitionVersionsResponse, MigrationPreview } from '../api/migrate'
import { ActionError } from '../api/actions'

let meData: { reauth?: { required?: boolean; windowSeconds?: number; freshUntil?: string } } = {}
vi.mock('../api/me', () => ({
  useMe: () => ({ data: meData }),
  roleOn: vi.fn(),
}))

const previewMutate = vi.fn()
const executeMutate = vi.fn()
let executeError: ActionError | undefined

vi.mock('../api/migrate', async () => {
  const actual = await vi.importActual<typeof import('../api/migrate')>('../api/migrate')
  return {
    ...actual,
    useDefinitionVersions: () => ({
      data: VERSIONS,
      isPending: false,
      isError: false,
      isSuccess: true,
    }),
    useMigratePreview: () => ({
      mutate: previewMutate,
      reset: vi.fn(),
      isPending: false,
      error: null,
    }),
    useMigrateExecute: () => ({
      mutate: executeMutate,
      reset: vi.fn(),
      isPending: false,
      error: executeError,
    }),
  }
})

import { MigrateModal } from './MigrateModal'

afterEach(() => {
  cleanup()
  previewMutate.mockClear()
  executeMutate.mockClear()
  meData = {}
  executeError = undefined
})

const VITALS: InstanceDetail = {
  compositeId: 'engine-a:pi-1',
  engineId: 'engine-a',
  processInstanceId: 'pi-1',
  businessKey: 'ORD-77',
  processDefinitionId: 'demoMigration:1:def-1',
  definitionKey: 'demoMigration',
  definitionVersion: 1,
  status: 'ACTIVE',
  flags: { ended: false, suspended: false },
}

const VERSIONS: DefinitionVersionsResponse = {
  engineId: 'engine-a',
  key: 'demoMigration',
  latestVersion: 2,
  totalVersions: 2,
  complete: true,
  versions: [
    { definitionId: 'demoMigration:1:def-1', version: 1, latest: false, runningInstanceCount: 1 },
    { definitionId: 'demoMigration:2:def-2', version: 2, latest: true, runningInstanceCount: 0 },
  ],
}

const PREVIEW: MigrationPreview = {
  engineId: 'engine-a',
  instanceId: 'pi-1',
  fromDefinitionId: 'demoMigration:1:def-1',
  fromDefinitionKey: 'demoMigration',
  fromVersion: 1,
  toProcessDefinitionId: 'demoMigration:2:def-2',
  toVersion: 2,
  engineValidated: false,
  executable: true,
  activities: [],
  targetActivities: [],
  activityStateDigest: 'digest-1',
  callActivityChildCount: 0,
  method: 'POST',
  enginePath: '/runtime/process-instances/pi-1/migrate',
  restBody: {},
  summary: 'All 1 active activit(ies) map.',
  banner: 'Inspector pre-check — not a Flowable validation.',
}

function renderModal() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MigrateModal
          engineId="engine-a"
          instanceId="pi-1"
          vitals={VITALS}
          engine={{ id: 'engine-a', environment: 'dev', mode: 'read-write' }}
          onClose={vi.fn()}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Drive the modal from step 1 into step 2 (the pre-check result) via "Check mapping". */
async function reachStepTwo() {
  previewMutate.mockImplementation(
    (_body: unknown, opts?: { onSuccess?: (r: MigrationPreview) => void }) => {
      opts?.onSuccess?.(PREVIEW)
    },
  )
  await waitFor(() => {
    expect(screen.getByRole('button', { name: /Check mapping/ })).toHaveProperty('disabled', false)
  })
  fireEvent.click(screen.getByRole('button', { name: /Check mapping/ }))
  await waitFor(() => {
    expect(screen.getByText(/All 1 active activit/)).toBeTruthy()
  })
}

describe('MigrateModal — dangerous-set reauth parity (issue #295)', () => {
  it('preview is never gated: a stale /api/me hint still lets "Check mapping" reach the pre-check', async () => {
    meData = { reauth: { required: true, windowSeconds: 900 } }
    renderModal()

    await reachStepTwo()

    // Reaching step 2 at all proves preview() was never blocked by the reauth hint.
    expect(screen.getByText(/All 1 active activit/)).toBeTruthy()
  })

  it('a stale /api/me hint disables Migrate and shows ReauthNotice instead of typing the reason', async () => {
    meData = { reauth: { required: true, windowSeconds: 900 } }
    renderModal()
    await reachStepTwo()

    // dev engine → needsToken is false (prod-only), so filling the reason is the only OTHER
    // gate; reauth must be the one still blocking the button below.
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'operator requested migration for INC-42' },
    })

    const confirm = screen.getByRole('button', { name: /^Migrate ORD-77 to v2$/ })
    expect(confirm).toHaveProperty('disabled', true)
    expect(screen.getByText(/needs a sign-in newer than 15 minutes/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Re-authenticate now/ })).toBeTruthy()
    expect(executeMutate).not.toHaveBeenCalled()
  })

  it('the reactive 401 challenge (reauth-required) also disables Migrate and swaps in ReauthNotice', async () => {
    meData = {}
    executeError = new ActionError({
      status: 401,
      code: 'reauth-required',
      title: 'Unauthorized',
      detail: 'Your sign-in is too old for this action.',
      outcome: 'refused',
    })
    renderModal()
    await reachStepTwo()

    expect(screen.getByText(/needs a sign-in newer than 15 minutes/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /^Migrate ORD-77 to v2$/ })).toHaveProperty(
      'disabled',
      true,
    )
  })

  it('a fresh session with no challenge leaves Migrate gated only by reason/token, never by reauth', async () => {
    meData = { reauth: { required: false, windowSeconds: 900 } }
    renderModal()
    await reachStepTwo()

    expect(screen.queryByText(/needs a sign-in newer than/)).toBeNull()

    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'operator requested migration for INC-42' },
    })

    expect(screen.getByRole('button', { name: /^Migrate ORD-77 to v2$/ })).toHaveProperty(
      'disabled',
      false,
    )
  })
})
