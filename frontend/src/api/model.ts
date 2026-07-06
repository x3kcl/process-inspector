// Type aliases over the GENERATED contract (src/api/schema.d.ts, `npm run gen:api`).
// Never widen or hand-extend these — regenerate after backend DTO changes (R-SEM-15).
import type { components } from './schema'

export type EngineDto = components['schemas']['EngineDto']
export type JobLanes = components['schemas']['JobLanes']
export type SearchRequest = components['schemas']['SearchRequest']
export type SearchResponse = components['schemas']['SearchResponse']
export type ProcessInstanceRow = components['schemas']['ProcessInstanceRow']
export type InstanceStatusFlags = components['schemas']['InstanceStatusFlags']
export type EngineResult = components['schemas']['EngineResult']
export type VariableFilter = components['schemas']['VariableFilter']

export type InstanceStatus = NonNullable<ProcessInstanceRow['status']>

export const ALL_STATUSES: readonly InstanceStatus[] = [
  'ACTIVE',
  'SUSPENDED',
  'COMPLETED',
  'FAILED',
  'RETRYING',
]

export function isInstanceStatus(value: string): value is InstanceStatus {
  return (ALL_STATUSES as readonly string[]).includes(value)
}
