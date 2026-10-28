import { Link, useLocation } from 'react-router-dom'
import { CheckIcon, ChevronRightIcon } from '../ui/Icons'
import { type Stage, type StageState, useFlow } from './flow'

function StageItem({ stage, state }: { stage: Stage; state: StageState }) {
  const content = (
    <>
      {state === 'done' && <CheckIcon width={14} height={14} className="text-[var(--success)]" />}
      <span>{stage.label}</span>
    </>
  )
  const base = 'flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm transition-colors'
  if (state === 'current') {
    return <span aria-current="step" className={`${base} bg-[var(--accent-soft)] font-semibold text-[var(--ink)]`}>{content}</span>
  }
  if (state === 'locked') {
    return <span aria-disabled="true" className={`${base} cursor-default text-[var(--muted)]/55`}>{content}</span>
  }
  return (
    <Link to={stage.to} className={`${base} text-[var(--muted)] hover:bg-[var(--line)]/50 hover:text-[var(--ink)]`}>
      {content}
    </Link>
  )
}

/** The flow across the top: Mode, Search and ranking, Documents, Apply, Submit. The current one is bold, the others are faded. */
export function FlowBar() {
  const { pathname } = useLocation()
  const { stages, state } = useFlow(pathname)
  return (
    <header className="sticky top-0 z-30 border-b border-[var(--line)]/70 bg-[var(--glass)] backdrop-blur-xl">
      <div className="mx-auto flex max-w-6xl items-center gap-6 px-6 py-3">
        <Link to="/" className="flex items-center gap-2 text-[15px] font-semibold tracking-tight">
          <img src="/jobpilot.svg" alt="" width={22} height={22} className="rounded-md" />
          JobPilot
        </Link>
        <nav aria-label="Pipeline" className="mx-auto">
          <ol className="flex items-center gap-1">
            {stages.map((stage, i) => (
              <li key={stage.key} className="flex items-center gap-1">
                <StageItem stage={stage} state={state(stage.key)} />
                {i < stages.length - 1 && <ChevronRightIcon width={14} height={14} className="text-[var(--muted)]/50" />}
              </li>
            ))}
          </ol>
        </nav>
        <span className="w-[88px]" aria-hidden />
      </div>
    </header>
  )
}
