import type { Entry } from '../../api/cv'

/** "2026-01" to "2026-05", or "2024" to "2026"; "present" for ongoing roles. */
export function formatRange(e: Pick<Entry, 'startYm' | 'endYm' | 'ongoing'>): string {
  const end = e.ongoing ? 'present' : e.endYm
  if (!e.startYm && !end) return ''
  return `${e.startYm ?? '?'} – ${end ?? '?'}`
}

const DATED_KINDS = new Set(['WORK', 'INTERNSHIP', 'EDUCATION'])

/** Why an entry needs the owner's attention: a to-do note or missing dates on a dated kind. */
export function attentionReasons(e: Entry): string[] {
  const reasons: string[] = []
  if (e.note) reasons.push(e.note)
  if (DATED_KINDS.has(e.kind) && !e.startYm && !e.endYm && !e.ongoing) reasons.push('Dates missing')
  return reasons
}

/** Newest first: ongoing, then by end date, then by start date; undated last. */
export function byRecency(a: Entry, b: Entry): number {
  const key = (e: Entry) => (e.ongoing ? '9999' : (e.endYm ?? e.startYm ?? '')) + '|' + (e.startYm ?? '')
  return key(b).localeCompare(key(a))
}
