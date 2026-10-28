const DAY = 24 * 60 * 60 * 1000

/** "2026-10-26" in local time, the same ISO shape the rest of the tool uses. */
export function shortDate(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** True when the deadline is in the future but at most three days away, or already passed. */
export function deadlineIsClose(iso: string | null, now: Date): boolean {
  if (!iso) return false
  return new Date(iso).getTime() - now.getTime() <= 3 * DAY
}

