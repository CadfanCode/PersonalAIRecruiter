import { useEffect, useRef, useState } from 'react'
import { useOnboarding } from '../api/onboarding'
import { closeTour, openTour, tourSeen, useTourOpen } from '../lib/tourStore'
import { Button } from './Form'

const STEPS = [
  { title: 'Welcome to JobPilot', text: 'JobPilot finds jobs for you, writes a CV and cover letter for each one, and fills in the application form. You always press the final button yourself.' },
  { title: 'Find jobs', text: 'On Mode, describe the roles you want, or let Claude suggest roles from your CV. You see the search plan before anything is searched.' },
  { title: 'Choose', text: 'Search and ranking scores every ad against your CV. Pick the ones worth applying to.' },
  { title: 'Documents', text: 'Each chosen job gets a CV and cover letter written only from facts in your CV database. Read them, change them, then approve.' },
  { title: 'Apply and submit', text: 'Apply fills the form in a browser tab and stops before the end. You review it and submit it yourself. Submit then records what you sent.' },
  { title: 'Your tools', text: 'The menu at the bottom of the screen holds your CV database, country, submitted applications, Help and Settings, including which Claude model each part uses.' },
] as const

/**
 * A short walk-through, offered once when setup is done and reopenable from Help. It is a dialog: Escape closes it, focus goes into it
 * and returns to where it was, and nothing moves when the system asks for less motion.
 */
export function Tour() {
  const onboarding = useOnboarding()
  const open = useTourOpen()
  const [step, setStep] = useState(0)
  const back = useRef<HTMLElement | null>(null)
  const primary = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (onboarding.data?.complete && !tourSeen()) openTour()
  }, [onboarding.data?.complete])

  useEffect(() => {
    if (!open) return
    back.current = document.activeElement as HTMLElement | null
    setStep(0)
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeTour()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      back.current?.focus?.()
    }
  }, [open])

  useEffect(() => {
    if (open) primary.current?.focus()
  }, [open, step])

  if (!open) return null
  const last = step === STEPS.length - 1
  const s = STEPS[step]
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4 backdrop-blur-[2px]" onMouseDown={(e) => { if (e.target === e.currentTarget) closeTour() }}>
      <div role="dialog" aria-modal="true" aria-labelledby="tour-title" className="animate-rise w-full max-w-md rounded-xl border border-[var(--line)] bg-[var(--panel)] p-7 shadow-[var(--shadow-lift)]">
        <p className="text-xs font-medium text-[var(--muted)]">{step + 1} of {STEPS.length}</p>
        <h2 id="tour-title" className="mt-2 text-xl font-semibold tracking-tight">{s.title}</h2>
        <p className="mt-2 text-[15px] leading-relaxed text-[var(--muted)]">{s.text}</p>
        <div className="mt-6 flex items-center gap-1.5" aria-hidden>
          {STEPS.map((_, i) => <span key={i} className={`h-1 flex-1 rounded-full ${i <= step ? 'bg-[var(--accent)]' : 'bg-[var(--line)]'}`} />)}
        </div>
        <div className="mt-6 flex items-center justify-between">
          <Button variant="quiet" onClick={closeTour}>Skip</Button>
          <div className="flex gap-2">
            {step > 0 && <Button variant="ghost" onClick={() => setStep(step - 1)}>Back</Button>}
            <button ref={primary} type="button" onClick={() => (last ? closeTour() : setStep(step + 1))}
              className="rounded-md bg-[var(--accent)] px-3.5 py-1.5 text-sm font-medium text-[var(--on-accent)] transition-colors hover:bg-[var(--accent-strong)]">
              {last ? 'Start' : 'Next'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
