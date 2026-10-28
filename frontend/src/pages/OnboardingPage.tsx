import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useCompleteOnboarding, useOnboarding } from '../api/onboarding'
import { ConnectStep } from './onboarding/ConnectStep'
import { CountryStep } from './onboarding/CountryStep'
import { ImportStep } from './onboarding/ImportStep'
import { ModeStep } from './onboarding/ModeStep'
import { Stepper } from './onboarding/Stepper'
import { UploadStep } from './onboarding/UploadStep'

const FIRST: Record<string, number> = { CV: 0, CLAUDE: 1, COUNTRY: 2, MODE: 3 }

/**
 * First-run setup, one step per screen: your CV, connect Claude, country, search mode. The CV files are held in the browser at
 * step 1 and read right after Claude is connected, because reading needs Claude. Returning users never see this.
 */
export function OnboardingPage() {
  const state = useOnboarding()
  const complete = useCompleteOnboarding()
  const navigate = useNavigate()
  const [step, setStep] = useState<number | null>(null)
  const [files, setFiles] = useState<File[]>([])
  const [reading, setReading] = useState(false)

  // Start at the first open step, once the state is known.
  useEffect(() => {
    if (step === null && state.data) setStep(state.data.nextStep ? FIRST[state.data.nextStep] : 0)
  }, [state.data, step])

  if (!state.data || step === null) {
    return <div className="flex min-h-screen items-center justify-center text-sm text-[var(--muted)]">Getting JobPilot ready…</div>
  }

  const s = state.data
  const finish = () => complete.mutate(undefined, { onSuccess: () => navigate('/', { replace: true }) })
  // After Claude is connected: read the held files, unless the CV database already has what it needs.
  const afterConnect = () => (files.length > 0 ? setReading(true) : setStep(2))

  return (
    <div className="min-h-screen">
      <div className="mx-auto flex max-w-3xl flex-col px-6 pb-16 pt-10">
        <div className="mb-10 flex items-center justify-center gap-2.5 text-[17px] font-semibold tracking-tight">
          <img src="/jobpilot.svg" alt="" width={28} height={28} className="rounded-lg" />
          JobPilot
        </div>
        <Stepper current={reading ? 1 : step} />
        <div key={`${step}-${reading}`} className="animate-rise mt-10 rounded-[28px] border border-[var(--line)] bg-[var(--panel)] p-8 shadow-[var(--shadow)] sm:p-10">
          {reading ? (
            <ImportStep files={files} onBack={() => { setReading(false); setStep(0) }} onDone={() => { setReading(false); setFiles([]); setStep(2) }} />
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
      </div>
    </div>
  )
}
