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
  /** The quotable support id (usability W1#6, R-AUD-04): the BFF stamps every error body
   *  — ProblemDetail AND the bare Spring 403/404 shape — with the request's X-Request-Id,
   *  which is also the audit rows' correlationId and the log lines' MDC id. */
  readonly requestId: string | undefined

  constructor(status: number, body: unknown) {
    super(ApiError.describe(status, body))
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.requestId = ApiError.requestIdOf(body)
  }

  private static describe(status: number, body: unknown): string {
    const sentence = ApiError.sentence(status, body)
    const requestId = ApiError.requestIdOf(body)
    // The next-move line rides every error banner rendered off error.message (W1#6) —
    // never invented client-side: only when the server sent one.
    return requestId === undefined
      ? sentence
      : `${sentence} Quote request ID ${requestId} to support.`
  }

  private static sentence(status: number, body: unknown): string {
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

  private static requestIdOf(body: unknown): string | undefined {
    if (body !== null && typeof body === 'object' && 'requestId' in body) {
      const id = (body as Record<string, unknown>)['requestId']
      if (typeof id === 'string' && id !== '') return id
    }
    return undefined
  }
}

const basicAuth: Middleware = {
  onRequest({ request }) {
    const token = getBasicAuth()
    if (token !== null) request.headers.set('Authorization', `Basic ${token}`)
    return request
  },
}

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

/**
 * The X-XSRF-TOKEN value a request must carry, or null to send nothing (usability W1#1).
 * Basic-per-request is CSRF-exempt on the BFF, but cookie-session users (dev ladder
 * sign-in form, OIDC) must echo Spring's `XSRF-TOKEN` cookie on unsafe methods — without
 * it Spring Security answers a bare 403 before the request reaches any handler.
 * Pure so vitest covers it in node; the middleware below is the thin DOM binding.
 */
export function xsrfHeaderValue(
  method: string,
  cookies: string,
  basicToken: string | null,
): string | null {
  if (basicToken !== null) return null
  if (!UNSAFE_METHODS.has(method.toUpperCase())) return null
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]+)/.exec(cookies)
  if (match === null) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    // Malformed percent-encoding must not crash the request — echo the raw value and
    // let the BFF's own token comparison decide (external review, both seats).
    return match[1]
  }
}

const cookieCsrf: Middleware = {
  onRequest({ request }) {
    const token = xsrfHeaderValue(request.method, document.cookie, getBasicAuth())
    if (token !== null) request.headers.set('X-XSRF-TOKEN', token)
    return request
  },
}

export const api = createClient<paths>({ baseUrl: '/' })
api.use(basicAuth)
api.use(cookieCsrf)
