import { describe, expect, it } from 'vitest'
import type { InstanceDetail } from '../api/model'
import { buildErrorTicketText, buildTicketText } from './ticket'

const FAILED: InstanceDetail = {
  definitionName: 'Order fulfilment',
  definitionVersion: 7,
  status: 'FAILED',
  businessKey: 'order-4711',
  whyStuck: {
    exceptionFirstLine: 'org.example.TaxTimeout: connect timed out\n  at deep.stack.Frame',
    failureTime: '2026-07-06T14:32:11Z',
  },
}

describe('buildTicketText', () => {
  it('emits every SPEC §4 line in order, plain text', () => {
    const text = buildTicketText(
      FAILED,
      'billing-prod:12345',
      'https://inspector/inspect/billing-prod/12345?tab=errors-jobs',
    )
    expect(text.split('\n')).toEqual([
      'Instance: billing-prod:12345',
      'Definition: Order fulfilment v7',
      'Status: FAILED',
      'Business key: order-4711',
      'Exception: org.example.TaxTimeout: connect timed out',
      'Last failure: 2026-07-06T14:32:11Z',
      'Link: https://inspector/inspect/billing-prod/12345?tab=errors-jobs',
    ])
  })

  it('keeps only the exception FIRST line', () => {
    const text = buildTicketText(FAILED, 'e:1', 'link')
    expect(text).not.toContain('deep.stack.Frame')
  })

  it('omits absent facts instead of printing undefined', () => {
    const text = buildTicketText({ status: 'ACTIVE' }, 'e:1', 'https://x/inspect/e/1')
    expect(text).toBe('Instance: e:1\nStatus: ACTIVE\nLink: https://x/inspect/e/1')
  })

  it('falls back through definitionName → key → processDefinitionId', () => {
    const text = buildTicketText({ processDefinitionId: 'order:7:abc' }, 'e:1', 'l')
    expect(text).toContain('Definition: order:7:abc')
  })
})

describe('buildErrorTicketText — copy-for-ticket when the page itself errored (W1#6, R-AUD-04)', () => {
  it('carries the composite id, the full error sentence (incl. the request id) and the link', () => {
    const text = buildErrorTicketText(
      'billing-prod:12345',
      'HTTP 404. Quote request ID req-4711 to support.',
      'https://inspector/inspect/billing-prod/12345',
    )
    expect(text.split('\n')).toEqual([
      'Instance: billing-prod:12345',
      'Error: HTTP 404. Quote request ID req-4711 to support.',
      'Link: https://inspector/inspect/billing-prod/12345',
    ])
  })
})
