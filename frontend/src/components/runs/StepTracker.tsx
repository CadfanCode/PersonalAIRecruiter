import type { Run } from '../../api/runs'

const STEPS = [
  { key: 'PROFILE', label: 'Profile' },
  { key: 'SEARCH', label: 'Search' },
  { key: 'RANK', label: 'Rank' },
  { key: 'DONE', label: 'Done' },
] as const

export function StepTracker({ run }: { run: Run }) {
  const current = STEPS.findIndex((s) => s.key === run.step)
  const failed = run.status === 'FAILED' || run.status === 'CANCELLED'
  return (
    <ol aria-label="Run steps" className="flex items-center gap-2">
      {STEPS.map((s, i) => {
        const done = i < current || run.status === 'DONE'
        const active = i === current && !done
        const style = done
          ? 'bg-navy text-white'
          : active
            ? failed
              ? 'bg-red-600 text-white'
              : 'border-2 border-[var(--accent)] text-[var(--accent)]'
            : 'border border-[var(--line)] text-[var(--muted)]'
        return (
          <li key={s.key} aria-current={active ? 'step' : undefined} className="flex items-center gap-2">
            <span className={`rounded-full px-3 py-1 text-sm font-medium ${style}`}>{s.label}</span>
            {i < STEPS.length - 1 && <span className="h-px w-6 bg-[var(--line)]" />}
          </li>
        )
      })}
    </ol>
  )
}
