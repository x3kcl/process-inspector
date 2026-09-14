// One source of truth for the feature clips: the recorder names them, make-titles.mjs
// renders their title cards, make-videos.sh encodes them, and build-deck.mjs places them.
// Order is presentation order. `poster` is the second into the RAW clip used as the
// PowerPoint cover image — without one you get pptxgenjs's grey play-button placeholder,
// and the clip's own title card duplicates the slide header.
export const CLIPS = [
  {
    name: 'triage', poster: 14, kicker: 'Stage 0 · triage',
    title: 'What is broken, how much, where',
    sub: 'Three engines on two Flowable majors in one view. Failures grouped by error class, not by instance — and every number stamped with its age.',
  },
  {
    name: 'search', poster: 13, kicker: 'Stage 1 · search',
    title: 'One failure class, every engine, one grid',
    sub: 'The whole search state lives in the URL, so the link in the ticket reproduces exactly this result set.',
  },
  {
    name: 'detail', poster: 36, kicker: 'Stage 2 · diagnose',
    title: 'Why is THIS one stuck',
    sub: 'The why-stuck strip, the diagram with the failing step marked, and five job lanes kept distinct — the lane is the diagnosis.',
  },
  {
    name: 'compare', poster: 31, kicker: 'Stage 2 · compare',
    title: 'Why did this one fail when its twin succeeded',
    sub: 'The Inspector suggests the most recent successful sibling by itself, then diffs path, variables and timing against it.',
  },
  {
    name: 'fix', poster: 30, kicker: 'Stage 3 · fix',
    title: 'The fix — and the rails around it',
    sub: 'Compare-and-set variable edit, a confirm button that restates the change instead of saying “OK”, then the retry. Verified COMPLETED on camera.',
  },
  {
    name: 'bulk', poster: 47, kicker: 'Stage 3 · bulk',
    title: 'A whole failure class at once, with guardrails',
    sub: 'Reason mandatory, scope re-resolved server-side at dispatch, capped — and every item reported individually in a job that survives a restart.',
  },
  {
    name: 'audit', poster: 22, kicker: 'Handover',
    title: 'Who did what, when, and why',
    sub: 'Every action lands in an append-only log — the authoritative WHO, the shift report, the thing the incumbent tool does not have.',
  },
  {
    name: 'ledger', poster: 36, kicker: 'Research track',
    title: 'The incident ledger and the self-heal evidence',
    sub: 'Persisted incidents with episodes and time-to-resolution — and a badge that refuses to guess until it has enough observations.',
  },
]
