// Dev-chain credentials (SecurityConfig dev profile: HTTP Basic against the ladder users).
// Held in sessionStorage only — never persisted, never logged. Requests carrying an
// Authorization header are CSRF-exempt on the BFF, so Basic-per-request keeps the SPA
// stateless. The oidc (prod) profile replaces this with a login redirect in a later slice.
const KEY = 'inspector.basic'

export function getBasicAuth(): string | null {
  return sessionStorage.getItem(KEY)
}

export function setBasicAuth(username: string, password: string): void {
  sessionStorage.setItem(KEY, btoa(`${username}:${password}`))
}

export function clearBasicAuth(): void {
  sessionStorage.removeItem(KEY)
}
