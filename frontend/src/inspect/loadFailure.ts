// Usability W2 #4 (theme T12, SPEC §10a honesty): the instance page's load-failure copy,
// COVERAGE-parameterized like the omnibox's "resolved against N of M engines" line. This
// route targets exactly ONE engine, so coverage is binary: a 404 means the engine ANSWERED
// (the BFF's pre-flight historic read ran and found nothing) — that is a definitive
// not-found and must NOT hedge; only a non-answer (gateway/unavailable/network) earns the
// "may be unreachable" hedge. Pure so vitest pins the wording per status class.

/** The sentence appended to the error message under the vitals header. */
export function instanceLoadFailureCopy(status: number | undefined): string {
  if (status === 404) {
    return (
      'The engine answered — resolved against 1 of 1 engines: no such instance exists there, ' +
      'live or historic (it was purged from history, or never existed on this engine). ' +
      'This is a confirmed not-found.'
    )
  }
  if (status === undefined || status === 502 || status === 503 || status === 504) {
    return (
      'The engine did not answer — it is unreachable or timing out, so this is ' +
      'NOT a confirmed not-found: the instance may still exist.'
    )
  }
  return (
    'The request was refused before the engine could be asked — this says nothing ' +
    'about whether the instance exists.'
  )
}
