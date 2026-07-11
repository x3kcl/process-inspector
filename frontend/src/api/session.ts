// Explicit sign-out signal (usability W3 dev-ladder surface). Distinct from the 401 chain in
// Shell's useAnyAuthError: a deliberate sign-out must force the SignIn form even though no
// cached query has answered 401 yet (the dev session cookie still authenticates until the
// server logout lands). A successful sign-in clears it. A tiny external store so both the
// header's Sign-out control and the Shell's SignIn gate read one source, via useSyncExternalStore.
let signedOut = false
const listeners = new Set<() => void>()

export function isSignedOut(): boolean {
  return signedOut
}

export function setSignedOut(value: boolean): void {
  if (signedOut === value) return
  signedOut = value
  for (const listener of listeners) listener()
}

export function subscribeSignedOut(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
