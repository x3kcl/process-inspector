// @vitest-environment jsdom
// #758: the dev sign-in validates credentials with GET /api/me (open to every signed-in user),
// never a gated fleet read — a user with no role must get signed in and land on the no-access
// state, not stay on the form with the no-engine-role refusal as a "sign-in failed" message.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SignIn } from './SignIn'

const get = vi.fn((path: string) =>
  Promise.resolve(
    path === '/api/me'
      ? { data: { username: 'pat', role: null, engineRoles: {} }, response: { status: 200 } }
      : { data: undefined, error: { code: 'no-engine-role' }, response: { status: 403 } },
  ),
)
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return { ...actual, api: { GET: (...a: [string]) => get(...a) } }
})

afterEach(() => {
  cleanup()
  get.mockClear()
})

describe('SignIn (#758)', () => {
  it('signs a user with no role in: credentials are checked on /api/me only', async () => {
    const client = new QueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    render(
      <QueryClientProvider client={client}>
        <SignIn />
      </QueryClientProvider>,
    )
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'pat' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'dev' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => {
      expect(invalidate).toHaveBeenCalled()
    })
    expect(get.mock.calls.map((c) => c[0])).toEqual(['/api/me'])
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
