import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useOnboarding } from '../../api/onboarding'
import { SETUP_STEPS, useSetup } from '../../setup/SetupProvider'
import { CheckIcon } from '../ui/Icons'
import { type StageState, useFlow } from './flow'
import { ThemeToggle } from './ThemeToggle'

type Look = 'current' | 'done' | 'available' | 'waiting'

const TAB = 'relative flex h-14 cursor-pointer items-center gap-2 px-3 text-sm transition-colors after:absolute after:inset-x-3 after:bottom-0 after:h-0.5 after:rounded-full'
const LOOK: Record<Look, string> = {
  current: 'font-semibold text-[var(--ink)] after:bg-[var(--accent)]',
  done: 'text-[var(--ink)] after:bg-transparent hover:after:bg-[var(--line-strong)]',
  available: 'text-[var(--muted)] after:bg-transparent hover:text-[var(--ink)] hover:after:bg-[var(--line-strong)]',
  waiting: 'text-[var(--muted)] after:bg-transparent hover:text-[var(--ink)]',
}

function Tab({ n, label, look }: { n: number; label: string; look: Look }) {
  return (
    <>
      <span className={`flex h-5 w-5 items-center justify-center rounded-full text-[11px] font-semibold tabular-nums ${
        look === 'done' ? 'bg-[var(--success)] text-white' : look === 'current' ? 'bg-[var(--accent)] text-[var(--on-accent)]' : 'bg-[var(--hover)] text-[var(--muted)]'}`}>
        {look === 'done' ? <CheckIcon width={11} height={11} strokeWidth={3} /> : n}
      </span>
      <span className="whitespace-nowrap">{label}</span>
    </>
  )
}

/** Before setup is finished: the four setup steps, each one a button. After: the five stages of the pipeline, each one a link. */
function Steps() {
  const onboarding = useOnboarding()
  const { pathname } = useLocation()
  const { stages, state } = useFlow(pathname)
  const setup = useSetup()
  const navigate = useNavigate()

  if (onboarding.data && !onboarding.data.complete) {
    const shown = setup.step === null ? 0 : setup.reading ? 1 : setup.step
    return (
      <ol aria-label="Setup progress" className="flex items-center">
        {SETUP_STEPS.map((label, i) => {
          const look: Look = pathname === '/welcome' && i === shown ? 'current' : i < setup.reachable ? 'done' : i === setup.reachable ? 'available' : 'waiting'
          return (
            <li key={label}>
              <button type="button" aria-current={look === 'current' ? 'step' : undefined} className={`${TAB} ${LOOK[look]}`}
                title={i > setup.reachable ? `Not started yet: complete ${SETUP_STEPS[setup.reachable]} first` : undefined}
                onClick={() => { setup.setReading(false); setup.goTo(i); navigate('/welcome') }}>
                <Tab n={i + 1} label={label} look={look} />
              </button>
            </li>
          )
        })}
      </ol>
    )
  }

  const lookOf = (s: StageState): Look => (s === 'locked' ? 'waiting' : s)
  return (
    <ol aria-label="Pipeline" className="flex items-center">
      {stages.map((stage, i) => {
        const look = lookOf(state(stage.key))
        return (
          <li key={stage.key}>
            <Link to={stage.to} aria-current={look === 'current' ? 'step' : undefined} className={`${TAB} ${LOOK[look]}`}
              title={look === 'waiting' ? 'Not started yet' : undefined}>
              <Tab n={i + 1} label={stage.label} look={look} />
            </Link>
          </li>
        )
      })}
    </ol>
  )
}

/** The bar across the top, on every screen: the brand, where you are in the process (every part of it clickable), and the theme switch. */
export function FlowBar() {
  return (
    <header className="sticky top-0 z-30 border-b border-[var(--line)] bg-[var(--panel)]">
      <div className="mx-auto flex h-14 max-w-[1800px] items-center gap-6 px-5">
        <Link to="/" className="flex shrink-0 items-center gap-2 text-[15px] font-semibold tracking-tight">
          <img src="/jobpilot.svg" alt="" width={22} height={22} className="rounded" />
          JobPilot
        </Link>
        <nav aria-label="Progress" className="min-w-0 flex-1 overflow-x-auto">
          <Steps />
        </nav>
        <ThemeToggle />
      </div>
    </header>
  )
}
