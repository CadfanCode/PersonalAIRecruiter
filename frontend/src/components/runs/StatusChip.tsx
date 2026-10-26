import type { JobStatus } from '../../api/runs'

// SPEC 12: new = grey, shortlisted = blue, docs ready = violet, applied = green, logged = teal, rejected = red.
const STYLES: Record<JobStatus, string> = {
  NEW: 'bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-200',
  SHORTLISTED: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-100',
  DOCS_READY: 'bg-violet-100 text-violet-800 dark:bg-violet-900 dark:text-violet-100',
  FILLED: 'bg-indigo-100 text-indigo-800 dark:bg-indigo-900 dark:text-indigo-100',
  APPLIED: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-100',
  LOGGED: 'bg-teal-100 text-teal-800 dark:bg-teal-900 dark:text-teal-100',
  REJECTED: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-100',
  ARCHIVED: 'bg-stone-200 text-stone-600 dark:bg-stone-700 dark:text-stone-300',
}

export function StatusChip({ status }: { status: JobStatus }) {
  return (
    <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${STYLES[status]}`}>
      {status.replace('_', ' ').toLowerCase()}
    </span>
  )
}
