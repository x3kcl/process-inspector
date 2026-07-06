// Mirrors the backend DTOs (backend/src/main/java/io/inspector/dto).

export type InstanceStatus = 'ACTIVE' | 'SUSPENDED' | 'COMPLETED' | 'FAILED'

export interface EngineInfo {
  id: string
  name: string
  environment: string
  color: string
  reachable: boolean
  engineVersion: string | null
  healthError: string | null
}

export interface VariableFilter {
  name: string
  value: string
  operation: string // equals | like | greaterThan | ...
  type?: string
}

export interface SearchRequest {
  engineIds: string[]
  statuses: InstanceStatus[]
  processDefinitionKey?: string
  businessKey?: string
  startedAfter?: string
  startedBefore?: string
  variables?: VariableFilter[]
  pageSize?: number
}

export interface ProcessInstanceRow {
  compositeId: string
  engineId: string
  engineName: string
  engineColor: string
  processInstanceId: string
  businessKey: string | null
  processDefinitionKey: string | null
  processDefinitionName: string | null
  status: InstanceStatus
  startTime: string | null
  endTime: string | null
  currentActivityOrError: string | null
}

export interface EngineResult {
  ok: boolean
  fetched: number
  total: number
  error: string | null
}

export interface SearchResponse {
  rows: ProcessInstanceRow[]
  perEngine: Record<string, EngineResult>
}
