// @vitest-environment jsdom
// #742: a secret-named variable arrives MASKED from the BFF ({ masked: true, no value, type kept,
// valueToken on the full read). Every view renders the distinct "hidden — secret-named" state as
// TEXT (not colour alone); the editor CASes with the opaque token and never shows either value;
// the sibling diff says "hidden" instead of same/changed; the raw-JSON export dumps the masked form.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { VariableDto } from '../../api/model'
import { fetchInstanceVariable } from '../../api/queries'
import { ToastProvider } from '../../components/toast'
import { changeLabel, isDivergent, renderValue } from '../comparison/diffFormat'
import { RawJsonExport } from '../RawJsonExport'
import { EditorPanel } from './editor/EditorPanel'
import { buildLedger, valuePreview } from './ledger'
import { VariableLedger } from './VariableLedger'

const HIDDEN = 'hidden — secret-named'

const action = vi.hoisted(() => ({
  mutate: vi.fn(),
  reset: vi.fn(),
  isPending: false,
  error: null,
}))

vi.mock('../../api/queries', () => ({
  fetchInstanceVariable: vi.fn(),
  fetchInstanceJobs: vi.fn().mockResolvedValue({ deadLetter: [] }),
}))

vi.mock('../../api/actions', () => ({
  useInstanceAction: () => action,
  dispatchInstanceAction: vi.fn(),
  ActionError: class ActionError extends Error {},
}))

const saveTextAs = vi.fn()
vi.mock('../../ops/exportCsv', () => ({
  saveTextAs: (...args: unknown[]) => saveTextAs(...args) as unknown,
}))

afterEach(() => {
  cleanup()
  action.mutate.mockReset()
})

const MASKED_ROW = {
  name: 'apiToken',
  type: 'string',
  masked: true,
  truncated: false,
  scope: 'global',
}

describe('the ledger', () => {
  it('previews a masked entry as the hidden state, in words', () => {
    const preview = valuePreview({
      name: 'apiToken',
      engineType: 'string',
      value: undefined,
      scope: 'process',
      masked: true,
    } as never)
    expect(preview.text).toBe(HIDDEN)
  })

  it('renders the hidden state for the masked row and the value for the plain one', () => {
    const groups = buildLedger([
      {
        name: 'apiToken',
        engineType: 'string',
        value: undefined,
        scope: 'process',
        masked: true,
      } as never,
      { name: 'amount', engineType: 'integer', value: 42, scope: 'process' },
    ])
    render(<VariableLedger groups={groups} />)
    const row = screen.getByText('apiToken').closest('tr')
    expect(row?.textContent).toContain(HIDDEN)
    expect(row?.textContent).not.toContain('(no value / null)')
    expect(screen.getByText('42')).toBeTruthy()
  })
})

describe('the sibling diff', () => {
  it('labels a MASKED pair as hidden and renders no value on either side', () => {
    expect(changeLabel('MASKED')).toBe(HIDDEN)
    expect(isDivergent('MASKED')).toBe(true)
    expect(renderValue(MASKED_ROW as VariableDto)).toBe(HIDDEN)
  })
})

describe('the raw-JSON export', () => {
  it('dumps the masked form, never a value', () => {
    const data = { processVariables: [MASKED_ROW], executionScopes: [], source: 'RUNTIME' }
    render(<RawJsonExport data={data} filename="vars.json" />)
    fireEvent.click(screen.getByTitle(/download the raw json/i))
    const [text] = saveTextAs.mock.calls[0] as [string]
    const row = (JSON.parse(text) as typeof data).processVariables[0]
    expect(row.masked).toBe(true)
    expect('value' in row).toBe(false)
  })
})

function mount(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <ToastProvider>{ui}</ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('editing a masked variable', () => {
  it('starts from an empty value, CASes with the token, and never shows old or new value', async () => {
    vi.mocked(fetchInstanceVariable).mockResolvedValue({
      name: 'apiToken',
      type: 'string',
      masked: true,
      valueToken: 'tk-742',
    })
    mount(
      <EditorPanel
        engineId="eng"
        instanceId="pi-1"
        entry={{ name: 'apiToken', scope: 'process', engineType: 'string', value: undefined }}
        engine={{ id: 'eng', name: 'Dev', environment: 'dev' }}
        onClose={vi.fn()}
      />,
    )
    expect((await screen.findAllByText(new RegExp(HIDDEN))).length).toBeGreaterThan(0)
    const input = screen.getByLabelText<HTMLInputElement>('new text value')
    expect(input.value).toBe('')
    fireEvent.change(input, { target: { value: 'tok-new-742' } })
    fireEvent.click(screen.getByRole('button', { name: 'Review change…' }))

    const confirm = await screen.findByRole('button', { name: /Change apiToken/ })
    await waitFor(() => {
      expect(confirm).toHaveProperty('disabled', false)
    })
    expect(document.body.textContent).not.toContain('tok-new-742')
    fireEvent.click(confirm)

    expect(action.mutate).toHaveBeenCalledOnce()
    const [{ body }] = action.mutate.mock.calls[0] as [
      { body: { variable: Record<string, unknown> } },
    ]
    expect(body.variable.expectedValueToken).toBe('tk-742')
    expect('expectedOldValue' in body.variable).toBe(false)
    expect(body.variable.value).toBe('tok-new-742')
  })
})
