import { Navigate, useNavigate } from 'react-router-dom'
import { useCompleteOnboarding, useOnboarding } from '../api/onboarding'
import { NotStarted } from '../components/NotStarted'
import { SETUP_STEPS, useSetup } from '../setup/SetupProvider'
import { ConnectStep } from './onboarding/ConnectStep'
import { CountryStep } from './onboarding/CountryStep'
import { ImportStep } from './onboarding/ImportStep'
import { ModeStep } from './onboarding/ModeStep'
import { UploadStep } from './onboarding/UploadStep'

/**
 * First-run setup, one step per screen: your CV, connect Claude, country, search mode. The steps are also in the bar at the top, so
 * they can be opened in any order up to the first one still to do. What is held in the browser lives in {@link useSetup}, so the
 * bottom menu can be used at any time without losing it. Once setup is finished this page is gone: the same things are changed
 * from the bottom menu (CV database, Country, Claude).
 */
export function OnboardingPage() {
  const state = useOnboarding()
  const complete = useCompleteOnboarding()
  const navigate = useNavigate()
  const { step, setStep, reachable, files, setFiles, reading, setReading } = useSetup()

  if (!state.data || step === null) {
    return <div className="flex min-h-[40vh] items-center justify-center text-sm text-[var(--muted)]">Getting JobPilot ready…</div>
  }
  if (state.data.complete) return <Navigate to="/" replace />

  const s = state.data
  const finish = () => complete.mutate(undefined, { onSuccess: () => navigate('/', { replace: true }) })
  // After Claude is connected: read the held files, unless the CV database already has what it needs.
  const afterConnect = () => (files.length > 0 ? setReading(true) : setStep(2))
  const shown = reading ? 1 : step

  return (
    <div className="mx-auto max-w-3xl">
      <div key={`${step}-${reading}`} className="animate-rise rounded-xl border border-[var(--line)] bg-[var(--panel)] p-8 shadow-[var(--shadow)] sm:p-10">
        {step > reachable ? (
          <NotStarted stage={SETUP_STEPS[step]} first={SETUP_STEPS[reachable]} onGo={() => { setReading(false); setStep(reachable) }} />
        ) : reading ? (
          <ImportStep onBack={() => { setReading(false); setStep(0) }} onDone={() => { setReading(false); setFiles([]); setStep(2) }} />
        ) : step === 0 ? (
          <UploadStep files={files} onFiles={setFiles} existingEntries={s.cv.entries} onNext={() => setStep(1)} />
        ) : step === 1 ? (
          <ConnectStep alreadyWorks={s.claude.works} onBack={() => setStep(0)} onNext={afterConnect} />
        ) : step === 2 ? (
          <CountryStep onBack={() => setStep(1)} onNext={() => setStep(3)} />
        ) : (
          <ModeStep selected={s.mode.chosen ? s.mode.mode : null} finishing={complete.isPending} onBack={() => setStep(2)} onFinish={finish} />
        )}
        {complete.isError && <p role="alert" className="mt-4 text-sm text-[var(--danger)]">{complete.error.message}</p>}
      </div>
      <p className="sr-only">Step {shown + 1} of {SETUP_STEPS.length}</p>
    </div>
  )
}
