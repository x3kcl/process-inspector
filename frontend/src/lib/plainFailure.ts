// Plain-language gloss over technical failure text (SPEC §6 error-copy rule): the raw
// exception class / message stays available (title, <details>) but the FIRST line an
// operator reads should never be a stack trace. First-hit substring table, in priority
// order — the network-plumbing signatures before the generic HTTP-status buckets.
interface GlossRule {
  pattern: RegExp
  message: string
}

const RULES: GlossRule[] = [
  {
    pattern: /ClosedChannelException|Connection reset|EOFException/,
    message: 'the connection to the engine dropped mid-request',
  },
  {
    pattern: /Connection refused|ConnectException/,
    message: 'the engine is not accepting connections',
  },
  { pattern: /timeout|timed out/i, message: 'the engine did not answer in time' },
  { pattern: /UnknownHostException/, message: "the engine's address could not be found" },
  { pattern: /circuit/i, message: 'paused after repeated failures — retrying automatically' },
  { pattern: /401|403/, message: "the engine rejected the inspector's credentials" },
  { pattern: /5\d\d/, message: 'the engine reported an internal error' },
]

/** Usability round 1, Theme F: never surface a raw stack trace as the first line. */
export function glossTechnicalMessage(raw: string | undefined): string {
  const text = raw ?? ''
  for (const rule of RULES) {
    if (rule.pattern.test(text)) return rule.message
  }
  return 'an unexpected error — see technical detail'
}
