import { CheckIcon } from './Icons'

/** A green tick for an approved document, an empty ring for a document that is not approved yet, a dash when there is none. */
export function ApprovalTick({ approved, exists, label }: { approved: boolean; exists: boolean; label: string }) {
  if (!exists) return <span aria-label={`${label}: none yet`} className="text-[var(--muted)]/60">–</span>
  return approved ? (
    <span role="img" aria-label={`${label}: approved`} className="flex h-6 w-6 items-center justify-center rounded-full bg-[var(--success)] text-[var(--surface)]">
      <CheckIcon width={14} height={14} strokeWidth={3} />
    </span>
  ) : (
    <span role="img" aria-label={`${label}: not approved`} className="h-6 w-6 rounded-full border border-[var(--line)]" />
  )
}
