// @vitest-environment jsdom
// #497: the Hierarchy tab is the door to the plan builder — ADMIN on this engine only, and the
// plan's root is the instance being looked at.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

const role = { current: 'ADMIN' }
vi.mock('../../api/me', () => ({
  useMe: () => ({ data: { username: 'someone', role: role.current } }),
  roleOn: () => role.current,
}))
vi.mock('../useInstanceQueries', () => ({
  useInstanceHierarchy: () => ({
    isPending: false,
    isError: false,
    data: {
      root: {
        processInstanceId: 'pi-1',
        requested: true,
        definitionKey: 'demoRoot',
        childTotal: 1,
        children: [{ processInstanceId: 'pi-2', definitionKey: 'demoA' }],
      },
    },
  }),
}))
vi.mock('../HierarchyMigrateModal', () => ({
  HierarchyMigrateModal: (props: { engineId: string; rootInstanceId: string }) => (
    <div role="dialog">
      plan for {props.engineId}:{props.rootInstanceId}
    </div>
  ),
}))

import HierarchyTab from './HierarchyTab'

afterEach(cleanup)

function renderTab() {
  render(
    <MemoryRouter>
      <HierarchyTab engineId="engine-a" instanceId="pi-1" />
    </MemoryRouter>,
  )
}

describe('HierarchyTab — plan builder door (#497)', () => {
  it('an ADMIN opens the plan builder for the instance being looked at', () => {
    role.current = 'ADMIN'
    renderTab()
    expect(screen.queryByRole('dialog')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Migrate this hierarchy…' }))

    expect(screen.getByRole('dialog').textContent).toBe('plan for engine-a:pi-1')
  })

  it('below ADMIN the button is disabled and says why', () => {
    role.current = 'OPERATOR'
    renderTab()

    const button = screen.getByRole('button', { name: 'Migrate this hierarchy…' })
    expect((button as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByRole('note').textContent).toContain(
      'Planning a hierarchy migration needs ADMIN on this engine.',
    )
    fireEvent.click(button)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
