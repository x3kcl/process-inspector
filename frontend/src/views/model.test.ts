import { describe, expect, it } from 'vitest'
import type { SearchRequest } from '../api/model'
import type { RecentSearch, SavedView } from './model'
import {
  RECENT_CAP,
  decodeEnvelope,
  describeSearch,
  encodeEnvelope,
  isRecentSearch,
  isSavedView,
  normalizeSearch,
  pushRecent,
  removeView,
  sameSearch,
  upsertView,
} from './model'

const view = (name: string, search = 'status=FAILED'): SavedView => ({
  id: `id-${name}`,
  name,
  search,
  createdAt: '2026-07-07T10:00:00.000Z',
})

const recent = (search: string, label = search): RecentSearch => ({
  search,
  label,
  at: '2026-07-07T10:00:00.000Z',
})

describe('versioned localStorage envelope', () => {
  it('round-trips items through encode/decode', () => {
    const items = [view('mine')]
    expect(decodeEnvelope(encodeEnvelope(items), isSavedView)).toEqual(items)
  })

  it('degrades to empty on missing key, corrupt JSON, wrong shape and unknown version', () => {
    expect(decodeEnvelope(null, isSavedView)).toEqual([])
    expect(decodeEnvelope('{not json', isSavedView)).toEqual([])
    expect(decodeEnvelope('"just a string"', isSavedView)).toEqual([])
    expect(decodeEnvelope('{"items":[]}', isSavedView)).toEqual([])
    expect(decodeEnvelope('{"version":2,"items":[]}', isSavedView)).toEqual([])
  })

  it('drops malformed items but keeps valid ones (v2 migration must never choke)', () => {
    const raw = JSON.stringify({
      version: 1,
      items: [view('good'), { name: 'no-search' }, 42, null],
    })
    expect(decodeEnvelope(raw, isSavedView)).toEqual([view('good')])
    const recents = JSON.stringify({ version: 1, items: [recent('a=1'), { search: 'a' }] })
    expect(decodeEnvelope(recents, isRecentSearch)).toEqual([recent('a=1')])
  })
})

describe('normalizeSearch', () => {
  it('makes param order irrelevant without touching values', () => {
    const a = 'status=FAILED%2CRETRYING&engines=engine-a&errorText=timeout%3A+read'
    const b = 'errorText=timeout%3A+read&engines=engine-a&status=FAILED%2CRETRYING'
    expect(normalizeSearch(a)).toBe(normalizeSearch(b))
    expect(sameSearch(a, b)).toBe(true)
    const params = new URLSearchParams(normalizeSearch(a))
    expect(params.get('errorText')).toBe('timeout: read')
    expect(params.get('status')).toBe('FAILED,RETRYING')
  })

  it('keeps JSON-valued vars params intact through normalization', () => {
    const vars = JSON.stringify([{ name: 'orderId', operation: 'equals', value: 'X&1=2' }])
    const search = new URLSearchParams({ vars, status: 'FAILED' }).toString()
    const decoded = new URLSearchParams(normalizeSearch(search)).get('vars')
    expect(decoded).toBe(vars)
  })

  it('distinguishes genuinely different searches', () => {
    expect(sameSearch('status=FAILED', 'status=RETRYING')).toBe(false)
    expect(sameSearch('status=FAILED', 'status=FAILED&engines=a')).toBe(false)
  })
})

describe('pushRecent', () => {
  it('prepends newest first', () => {
    const list = pushRecent([recent('a=1')], recent('b=2'))
    expect(list.map((r) => r.search)).toEqual(['b=2', 'a=1'])
  })

  it('dedupes on the canonical search string — re-running moves it to the front', () => {
    const start = [recent('a=1&b=2', 'old label'), recent('c=3')]
    const list = pushRecent(start, recent('b=2&a=1', 'new label'))
    expect(list).toHaveLength(2)
    expect(list[0].label).toBe('new label')
    expect(list[1].search).toBe('c=3')
  })

  it(`caps at ${String(RECENT_CAP)} entries, evicting the oldest`, () => {
    let list: RecentSearch[] = []
    for (let i = 0; i < RECENT_CAP + 3; i++) {
      list = pushRecent(list, recent(`n=${String(i)}`))
    }
    expect(list).toHaveLength(RECENT_CAP)
    expect(list[0].search).toBe(`n=${String(RECENT_CAP + 2)}`)
    expect(list.at(-1)?.search).toBe('n=3')
  })
})

describe('saved views list operations', () => {
  it('appends a new name, replaces an existing one in place', () => {
    const list = upsertView([view('first'), view('second')], view('third'))
    expect(list.map((v) => v.name)).toEqual(['first', 'second', 'third'])
    const replaced = upsertView(list, { ...view('second', 'status=SUSPENDED'), id: 'id-new' })
    expect(replaced.map((v) => v.name)).toEqual(['first', 'second', 'third'])
    expect(replaced[1].search).toBe('status=SUSPENDED')
    expect(replaced[1].id).toBe('id-new')
  })

  it('removes by id only', () => {
    const list = [view('a'), view('b')]
    expect(removeView(list, 'id-a').map((v) => v.name)).toEqual(['b'])
    expect(removeView(list, 'missing')).toEqual(list)
  })
})

describe('describeSearch', () => {
  it('builds the compact criteria label', () => {
    const request: SearchRequest = {
      statuses: ['FAILED'],
      engineIds: ['billing-prod'],
      variables: [{ name: 'orderId', operation: 'equals', value: '123' }],
    }
    expect(describeSearch(request)).toBe('FAILED · billing-prod · orderId: 123')
  })

  it('covers the remaining categories with distinct markers', () => {
    const request: SearchRequest = {
      statuses: ['FAILED', 'RETRYING'],
      processDefinitionKey: 'orderFulfilment',
      businessKeyLike: 'ORD-',
      currentActivity: 'chargeCard',
      errorText: 'SocketTimeout',
      failureTimeAfter: '2026-07-07T09:00:00.000Z',
    }
    expect(describeSearch(request)).toBe(
      'FAILED+RETRYING · orderFulfilment · ~ORD- · @chargeCard · "SocketTimeout" · failed ≥ 2026-07-07 09:00Z',
    )
  })

  it('prefers the exact business key over the like-filter and falls back to "all instances"', () => {
    expect(describeSearch({ businessKey: 'ORD-4711', businessKeyLike: 'ORD-' })).toBe('ORD-4711')
    expect(describeSearch({})).toBe('all instances')
  })
})
