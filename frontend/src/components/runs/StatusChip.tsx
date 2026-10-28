import type { JobStatus } from '../../api/runs'
import { Pill, type Tone } from '../ui/Pill'

// new = grey, shortlisted = blue, docs ready = blue, filled = blue, applied = green, logged = green, rejected = red.
const TONES: Record<JobStatus, Tone> = {
  NEW: 'neutral',
  SHORTLISTED: 'accent',
  DOCS_READY: 'accent',
  FILLED: 'accent',
  APPLIED: 'success',
  LOGGED: 'success',
  REJECTED: 'danger',
  ARCHIVED: 'neutral',
}

export function StatusChip({ status }: { status: JobStatus }) {
  return <Pill tone={TONES[status]}>{status.replace('_', ' ').toLowerCase()}</Pill>
}
