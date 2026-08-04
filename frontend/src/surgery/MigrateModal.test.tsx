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
import type { DefinitionVersionsResponse, MigrationFinding, MigrationPreview } from '../api/migrate'
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

/**
 * The same instance, same target, same everything — but the pre-check now carries typed
 * findings (INSTANCE-MIGRATION.md §14): two WARNINGs from the calibrated blocker→warning
 * downgrades and the instance-level BOUNDARY_CLOCK_RESET info. Still `executable: true`:
 * warnings and info never block, and no rail moves either way.
 */
const PREVIEW_WITH_FINDINGS: MigrationPreview = {
  ...PREVIEW,
  summary: 'All 3 active activit(ies) map. 2 advisory warning(s).',
  activities: [
    {
      fromActivityId: 'bndC',
      fromType: 'boundaryEvent',
      status: 'BOUNDARY_REMOVED',
      blocker: false,
      warning: true,
      detail: 'The boundary event bndC is gone from the target version.',
      findings: [
        {
          code: 'BOUNDARY_SUBSCRIPTION_REMOVED',
          severity: 'WARNING',
          activityId: 'bndC',
          detail: 'the deadline protection disappears at migrate, with no error anywhere.',
        },
      ],
    },
    {
      fromActivityId: 'scopeA',
      fromType: 'subProcess',
      status: 'SCOPE_REMOVED',
      blocker: false,
      warning: true,
      detail: 'The subprocess scope scopeA is gone from the target version.',
      findings: [
        {
          code: 'ACTIVE_SCOPE_REMOVED',
          severity: 'WARNING',
          activityId: 'scopeA',
          detail: 'the enclosing region dissolves and its single live token re-homes outward.',
        },
      ],
    },
    {
      fromActivityId: 'stepC',
      fromType: 'userTask',
      status: 'AUTO_MAPPED',
      blocker: false,
      warning: false,
      detail: 'Maps by name.',
      findings: [],
    },
  ],
  findings: [
    {
      code: 'BOUNDARY_CLOCK_RESET',
      severity: 'INFO',
      detail: "a timer's clock MAY restart from the migrate call.",
    },
  ],
}

/** A blocked estimate: the one refusal — the BFF has nothing sendable for this token. */
const PREVIEW_BLOCKED: MigrationPreview = {
  ...PREVIEW,
  executable: false,
  summary: "1 active activit(ies) can't be auto-mapped — pick a target for each.",
  activities: [
    {
      fromActivityId: 'miScope',
      fromType: 'subProcess',
      status: 'FLAGGED_UNMAPPED',
      blocker: true,
      warning: false,
      detail: "No activity with id 'miScope' exists in the target version.",
      findings: [
        {
          code: 'UNMAPPED_ACTIVE_ACTIVITY',
          severity: 'BLOCKER_ADVICE',
          activityId: 'miScope',
          detail:
            'The Inspector cannot build a migration instruction for this activity — there is nothing to send.',
        },
      ],
    },
  ],
  targetActivities: [{ id: 'stepM', name: 'Step M', type: 'userTask' }],
  findings: [],
}

/**
 * The OTHER blocked shape (§14.11): a dissolving scope holding two concurrent tokens. Also
 * `blocker: true`, but its status is SCOPE_REMOVED and no mapping can fix it — so it must NOT
 * appear in the "pick a target" table.
 */
const PREVIEW_BLOCKED_TOKEN_LOSS: MigrationPreview = {
  ...PREVIEW,
  executable: false,
  summary:
    '1 subprocess scope(s) would collapse with more than one live token inside — migrating would silently destroy live work, and no mapping can prevent it.',
  activities: [
    {
      fromActivityId: 'scopeP',
      fromType: 'subProcess',
      status: 'SCOPE_REMOVED',
      blocker: true,
      warning: false,
      detail: "The subprocess scope 'scopeP' is gone and 2 live tokens are inside it.",
      findings: [
        {
          code: 'SCOPE_COLLAPSE_TOKEN_LOSS',
          severity: 'BLOCKER_ADVICE',
          activityId: 'scopeP',
          detail:
            'Migrating would DESTROY live work. 2 live tokens are inside it — the engine will keep 1.',
        },
      ],
    },
  ],
  targetActivities: [{ id: 'stepP1', name: 'Step P1', type: 'userTask' }],
  findings: [],
}

/**
 * A finding whose severity this build does not know — e.g. the BFF bumped `taxonomyVersion`
 * ahead of the SPA. It must still be SEEN.
 */
const PREVIEW_UNKNOWN_SEVERITY: MigrationPreview = {
  ...PREVIEW,
  activities: [
    {
      fromActivityId: 'stepX',
      fromType: 'userTask',
      status: 'AUTO_MAPPED',
      blocker: false,
      warning: false,
      detail: 'Maps by name.',
      // Deliberately outside the generated union — that is the whole point of the case.
      findings: [
        {
          code: 'SOMETHING_NEWER',
          severity: 'CAUTION',
          activityId: 'stepX',
          detail: 'a severity this build has never heard of.',
        },
      ] as unknown as MigrationFinding[],
    },
  ],
  findings: [],
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
async function reachStepTwo(preview: MigrationPreview = PREVIEW) {
  previewMutate.mockImplementation(
    (_body: unknown, opts?: { onSuccess?: (r: MigrationPreview) => void }) => {
      opts?.onSuccess?.(preview)
    },
  )
  await waitFor(() => {
    expect(screen.getByRole('button', { name: /Check mapping/ })).toHaveProperty('disabled', false)
  })
  fireEvent.click(screen.getByRole('button', { name: /Check mapping/ }))
  await waitFor(() => {
    expect(screen.getByText(preview.summary ?? '')).toBeTruthy()
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

describe('MigrateModal — typed pre-flight findings (INSTANCE-MIGRATION.md §14, issue #355)', () => {
  it('renders findings grouped by severity, each with its code, activity and honest detail', async () => {
    renderModal()
    await reachStepTwo(PREVIEW_WITH_FINDINGS)

    expect(screen.getByText(/Migrates, but look first \(2\)/)).toBeTruthy()
    expect(screen.getByText('ACTIVE_SCOPE_REMOVED')).toBeTruthy()
    expect(screen.getByText('BOUNDARY_SUBSCRIPTION_REMOVED')).toBeTruthy()
    expect(screen.getByText(/the enclosing region dissolves/)).toBeTruthy()
    expect(screen.getByText(/deadline protection disappears at migrate/)).toBeTruthy()

    // The instance-level INFO is its own, non-alarming group.
    expect(screen.getByText(/Consequences of migrating this instance \(1\)/)).toBeTruthy()
    expect(screen.getByText('BOUNDARY_CLOCK_RESET')).toBeTruthy()
    expect(screen.getByText(/clock MAY restart/)).toBeTruthy()

    // …and the estimate is labelled as an estimate, never as an engine verdict.
    expect(
      screen.getByText(/BFF estimate — the engine is the only ground truth at execute\./),
    ).toBeTruthy()
    expect(screen.getByText(/not a Flowable validation/)).toBeTruthy()
  })

  it('a BLOCKER_ADVICE is worded as our own refusal, never as an engine verdict', async () => {
    renderModal()
    await reachStepTwo(PREVIEW_BLOCKED)

    expect(screen.getByText(/Blocked before the engine \(1\)/)).toBeTruthy()
    expect(screen.getByText('UNMAPPED_ACTIVE_ACTIVITY')).toBeTruthy()
    expect(screen.getByText(/there is nothing to send/)).toBeTruthy()
    // Blocked ⇒ the targeted mapping dropdown, and no execute button at all.
    expect(screen.getByLabelText('target for miScope')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^Migrate ORD-77 to v2$/ })).toBeNull()
  })

  it('the token-loss blocker is loud but offers NO mapping dropdown — a mapping cannot fix it', async () => {
    renderModal()
    await reachStepTwo(PREVIEW_BLOCKED_TOKEN_LOSS)

    expect(screen.getByText(/Blocked before the engine \(1\)/)).toBeTruthy()
    expect(screen.getByText('SCOPE_COLLAPSE_TOKEN_LOSS')).toBeTruthy()
    expect(screen.getByText(/Migrating would DESTROY live work/)).toBeTruthy()
    // …and NOT in the "pick a target for each" table: offering a select here would invite the
    // operator to try the one thing the BFF says outright does not help (§14.11).
    expect(screen.queryByLabelText('target for scopeP')).toBeNull()
    expect(screen.queryByText(/can’t be auto-mapped/)).toBeNull()
    expect(screen.queryByRole('button', { name: /^Migrate ORD-77 to v2$/ })).toBeNull()
  })

  it('an UNRECOGNIZED severity still renders — the buckets fail toward visible, never silent', async () => {
    renderModal()
    await reachStepTwo(PREVIEW_UNKNOWN_SEVERITY)

    // It lands in the WARNING callout and is COUNTED in that heading, rather than rendering in
    // no callout and counting in no heading (the pre-fix three-literal-filters behavior).
    expect(screen.getByText(/Migrates, but look first \(1\)/)).toBeTruthy()
    expect(screen.getByText('SOMETHING_NEWER')).toBeTruthy()
    expect(screen.getByText(/a severity this build has never heard of/)).toBeTruthy()
  })

  it('a zero-finding pre-check renders no findings block and no estimate label', async () => {
    renderModal()
    await reachStepTwo(PREVIEW)

    expect(screen.queryByText(/Migrates, but look first/)).toBeNull()
    expect(screen.queryByText(/Consequences of migrating this instance/)).toBeNull()
    expect(screen.queryByText(/BFF estimate — the engine is/)).toBeNull()
  })

  /**
   * The advisory-only invariant (§14.6 / re-lock decision 3) as the UI enforces it: a green
   * estimate and a warning-carrying one produce IDENTICAL guard behavior. Findings change what
   * the operator READS, never what the modal DOES.
   */
  it('the tier-3 guard behavior is identical for a green and a warning-carrying estimate', async () => {
    for (const preview of [PREVIEW, PREVIEW_WITH_FINDINGS]) {
      cleanup()
      meData = { reauth: { required: false, windowSeconds: 900 } }
      renderModal()
      await reachStepTwo(preview)

      // IRREVERSIBLE badge — byte-for-byte unchanged.
      expect(screen.getByText('IRREVERSIBLE')).toBeTruthy()

      // The reason rail still gates execute, and a warning never pre-satisfies it.
      const confirm = screen.getByRole('button', { name: /^Migrate ORD-77 to v2$/ })
      expect(confirm).toHaveProperty('disabled', true)
      expect(executeMutate).not.toHaveBeenCalled()

      fireEvent.change(screen.getByLabelText(/Reason/), {
        target: { value: 'operator requested migration for INC-42' },
      })
      expect(screen.getByRole('button', { name: /^Migrate ORD-77 to v2$/ })).toHaveProperty(
        'disabled',
        false,
      )

      // …and the execute body still carries the mandatory compare-and-set binding, unchanged.
      fireEvent.click(screen.getByRole('button', { name: /^Migrate ORD-77 to v2$/ }))
      expect(executeMutate).toHaveBeenCalledTimes(1)
      expect(executeMutate.mock.calls[0][0]).toMatchObject({
        expectedFromDefinitionId: 'demoMigration:1:def-1',
        expectedActivityStateDigest: 'digest-1',
        reason: 'operator requested migration for INC-42',
      })
      executeMutate.mockClear()
    }
  })
})
