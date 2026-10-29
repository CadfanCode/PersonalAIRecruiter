import type { Score } from '../../api/runs'

const MAX = { skills: 50, experience: 30, eligibility: 20 } as const

/** Stacked bar of the three rubric parts; the tooltip lists them. Blank when the ad is not scored. */
export function ScoreBar({ score }: { score: Score | null }) {
  if (!score) return <span className="text-xs text-[var(--muted)]">not scored</span>
  const tip = `Skills ${score.skills}/${MAX.skills} · Experience ${score.experience}/${MAX.experience} · Eligibility ${score.eligibility}/${MAX.eligibility}`
  return (
    <div className="flex items-center gap-1.5" title={tip}>
      <span className="w-6 shrink-0 text-right text-sm font-semibold tabular-nums">{score.total}</span>
      <div role="img" aria-label={tip} className="flex h-2 w-12 shrink-0 overflow-hidden rounded-full bg-[var(--line)]">
        <span className="bg-[var(--accent)]" style={{ width: `${score.skills}%` }} />
        <span className="bg-[var(--accent)]/65" style={{ width: `${score.experience}%` }} />
        <span className="bg-[var(--accent)]/35" style={{ width: `${score.eligibility}%` }} />
      </div>
    </div>
  )
}

export function ScoreBreakdown({ score }: { score: Score }) {
  const rows: [string, number, number, string][] = [
    ['Skills', score.skills, MAX.skills, 'bg-[var(--accent)]'],
    ['Experience', score.experience, MAX.experience, 'bg-[var(--accent)]/65'],
    ['Eligibility', score.eligibility, MAX.eligibility, 'bg-[var(--accent)]/35'],
  ]
  return (
    <dl className="grid gap-2">
      {rows.map(([label, value, max, color]) => (
        <div key={label}>
          <dt className="flex justify-between text-xs text-[var(--muted)]">
            <span>{label}</span>
            <span>
              {value}/{max}
            </span>
          </dt>
          <dd className="h-2 overflow-hidden rounded-full bg-[var(--line)]">
            <div className={`h-full ${color}`} style={{ width: `${(value / max) * 100}%` }} />
          </dd>
        </div>
      ))}
    </dl>
  )
}
