// The ONE HTTP client of the app. Every call goes through openapi-fetch against the
// generated contract — hand-rolled fetch wrappers are forbidden (R-SEM-15).
import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './schema'
import { getBasicAuth } from './auth'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, body: unknown) {
    super(ApiError.describe(status, body))
    this.name = 'ApiError'
    this.status = status
  }

  private static describe(status: number, body: unknown): string {
    if (status === 401) return 'Not signed in'
    // SearchController answers bad filter input as 400 {"error": "<message>"}.
    if (body !== null && typeof body === 'object' && 'error' in body) {
      const message = body.error
      if (typeof message === 'string') return message
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
