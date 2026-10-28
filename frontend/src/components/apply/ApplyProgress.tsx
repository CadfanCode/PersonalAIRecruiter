import { type ApplyStage, STAGES, type TabView } from '../../api/apply'

/**
 * The stages of one application as a bar: the ones done are filled, the current one is named, and a stage that failed is red.
 * It reflects the real stage the backend is in, never a timer.
 */
export function ApplyProgress({ tab }: { tab: TabView }) {
  const failed = tab.failedStage
  const current: ApplyStage = failed ?? tab.stage
  const index = Math.max(0, STAGES.findIndex((s) => s.id === current))
  const done = tab.status === 'READY_FOR_USER'
  return (
    <div className="mt-4">
      <div role="progressbar" aria-label="Application progress" aria-valuemin={1} aria-valuemax={STAGES.length} aria-valuenow={index + 1}
        aria-valuetext={failed ? `Stopped while ${STAGES[index].label.toLowerCase()}` : STAGES[index].label}
        className="flex gap-1">
        {STAGES.map((s, i) => {
          const state = failed && i === index ? 'failed' : i < index || done ? 'done' : i === index ? 'current' : 'todo'
          return (
            <span key={s.id} data-state={state}
              className={`h-1.5 flex-1 rounded-full ${state === 'failed' ? 'bg-[var(--danger)]' : state === 'done' ? 'bg-[var(--success)]' : state === 'current' ? 'animate-pulse bg-[var(--accent)]' : 'bg-[var(--line)]'}`} />
          )
        })}
      </div>
      <ol className="mt-1.5 hidden justify-between gap-1 text-[11px] text-[var(--muted)] md:flex" aria-hidden>
        {STAGES.map((s, i) => (
          <li key={s.id} className={`flex-1 ${i === index ? (failed ? 'font-semibold text-[var(--danger)]' : 'font-semibold text-[var(--ink)]') : ''}`}>{s.label}</li>
        ))}
      </ol>
      <p className="mt-1 text-xs font-medium md:hidden">{failed ? `Stopped while ${STAGES[index].label.toLowerCase()}` : STAGES[index].label}</p>
    </div>
  )
}
