/**
 * R-UXQ-02 route-change focus (usability W1#5, theme T4): after SPA navigation the old
 * page unmounts and the browser drops focus onto <body>, forcing keyboard users to
 * re-Tab from the page top after every route change.
 *
 * If — and only if — focus was lost to <body>, move it to the new route's main heading,
 * falling back to the <main> landmark and then the app container ("nearest survivor").
 * Focus that survived navigation (e.g. the topbar link the user just activated, or a
 * detail tab button on a ?tab= switch) is never stolen (R-UXQ-06: nothing steals focus
 * uninvited).
 *
 * Returns the element focus moved to, or null when nothing was done.
 */
export function restoreRouteFocus(doc: Document = document): HTMLElement | null {
  const active = doc.activeElement
  if (active !== null && active !== doc.body) return null // a survivor holds focus
  const target =
    doc.querySelector<HTMLElement>('main h1, main h2') ??
    doc.querySelector<HTMLElement>('main') ??
    doc.querySelector<HTMLElement>('.app')
  if (target === null) return null
  // Headings/landmarks are not natively focusable — grant programmatic focusability
  // without adding them to the Tab order (and never clobber an authored tabindex).
  if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1')
  target.focus()
  return doc.activeElement === target ? target : null
}
