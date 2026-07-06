// The ONE HTTP client of the app. Every call goes through openapi-fetch against the
// generated contract — hand-rolled fetch wrappers are forbidden (R-SEM-15).
import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './schema'
import { getBasicAuth } from './auth'

export class ApiError extends Error {
  readonly status: number
  /** The parsed error body — action endpoints answer RFC-7807 ProblemDetails whose
   *  machine-readable `code`/`outcome` properties drive the guard-ladder UI copy. */
  readonly body: unknown

  constructor(status: number, body: unknown) {
    super(ApiError.describe(status, body))
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }

  private static describe(status: number, body: unknown): string {
    if (status === 401) return 'Not signed in'
    if (body !== null && typeof body === 'object') {
      // SearchController answers bad filter input as 400 {"error": "<message>"}.
      if ('error' in body && typeof body.error === 'string') return body.error
      // ProblemDetail: detail carries the operator-facing sentence.
      if ('detail' in body && typeof body.detail === 'string') return body.detail
      if ('title' in body && typeof body.title === 'string') return body.title
    }
    return `HTTP ${String(status)}`
  }
}

const basicAuth: Middleware = {
  onRequest({ request }) {
    const token = getBasicAuth()
    if (token !== null) request.headers.set('Authorization', `Basic ${token}`)
    return request
  },
}

export const api = createClient<paths>({ baseUrl: '/' })
api.use(basicAuth)
