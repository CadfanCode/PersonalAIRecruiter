import { CheckIcon } from '../../components/ui/Icons'

export const STEPS = ['Your CV', 'Connect Claude', 'Country', 'Mode'] as const

/** Four dots with labels; finished steps get a tick. */
export function Stepper({ current }: { current: number }) {
  return (
    <ol aria-label="Setup progress" className="flex items-center justify-center gap-2">
      {STEPS.map((label, i) => {
        const done = i < current
        const active = i === current
        return (
          <li key={label} aria-current={active ? 'step' : undefined} className="flex items-center gap-2">
            <span className={`flex items-center gap-2 rounded-full px-3 py-1.5 text-sm transition ${
              active ? 'bg-[var(--accent-soft)] font-semibold text-[var(--ink)]' : done ? 'text-[var(--ink)]' : 'text-[var(--muted)]/60'}`}>
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-[11px] font-semibold ${
                done ? 'bg-[var(--success)] text-white' : active ? 'bg-navy text-white' : 'bg-[var(--line)] text-[var(--muted)]'}`}>
                {done ? <CheckIcon width={12} height={12} strokeWidth={3} /> : i + 1}
              </span>
              <span className="hidden sm:inline">{label}</span>
            </span>
            {i < STEPS.length - 1 && <span className="h-px w-6 bg-[var(--line)]" />}
          </li>
        )
      })}
    </ol>
  )
}
