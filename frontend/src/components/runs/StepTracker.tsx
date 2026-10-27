import type { Run } from '../../api/runs'

const STEPS = [
  { key: 'PROFILE', label: 'Profile' },
  { key: 'SEARCH', label: 'Search' },
  { key: 'RANK', label: 'Rank' },
  { key: 'DONE', label: 'Done' },
] as const

const DISCOVER_STEPS = [STEPS[0], { key: 'SUGGEST', label: 'Roles' }, ...STEPS.slice(1)] as const

export function StepTracker({ run }: { run: Run }) {
  const steps: readonly { key: string; label: string }[] = run.options.mode === 'DISCOVER' ? DISCOVER_STEPS : STEPS
  const current = steps.findIndex((s) => s.key === run.step)
  const failed = run.status === 'FAILED' || run.status === 'CANCELLED'
  return (
    <ol aria-label="Run steps" className="flex items-center gap-2">
      {steps.map((s, i) => {
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
            {i < steps.length - 1 && <span className="h-px w-6 bg-[var(--line)]" />}
          </li>
        )
      })}
    </ol>
  )
}
