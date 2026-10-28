import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { type OnboardingStep, useOnboarding } from '../api/onboarding'

export const SETUP_STEPS = ['Your CV', 'Connect Claude', 'Country', 'Mode'] as const

const FIRST: Record<OnboardingStep, number> = { CV: 0, CLAUDE: 1, COUNTRY: 2, MODE: 3 }

interface SetupValue {
  /** The step on screen; null until the server has said where setup stands. */
  step: number | null
  /** Moves on in the flow (Continue, Back): the step counts as reached. */
  setStep: (step: number) => void
  /** Opens a step from the top bar: jumping ahead does not count as reaching it. */
  goTo: (step: number) => void
  /** The furthest step the person may open: the first one still to do, or further if they already got there. */
  reachable: number
  /** CV files picked at step 1, held here until Claude is connected and they can be read. */
  files: File[]
  setFiles: (files: File[]) => void
  /** Reading the held files is a screen of its own, between Claude and the country. */
  reading: boolean
  setReading: (reading: boolean) => void
}

const SetupContext = createContext<SetupValue | null>(null)

export function useSetup() {
  const v = useContext(SetupContext)
  if (!v) throw new Error('useSetup needs a SetupProvider')
  return v
}

/**
 * Everything setup holds in the browser lives here, above the routes, so opening the bottom menu, Settings or the CV database in
 * the middle of setup never loses the files or the step. The reading itself runs on the server (see useImportJob).
 */
export function SetupProvider({ children }: { children: ReactNode }) {
  const onboarding = useOnboarding()
  const [step, setStepState] = useState<number | null>(null)
  const [furthest, setFurthest] = useState(0)
  const [files, setFiles] = useState<File[]>([])
  const [reading, setReading] = useState(false)

  const serverFirst = onboarding.data?.nextStep ? FIRST[onboarding.data.nextStep] : 3
  const reachable = Math.max(furthest, serverFirst)

  // Start at the first open step, once the state is known.
  useEffect(() => {
    if (step === null && onboarding.data) setStepState(onboarding.data.nextStep ? FIRST[onboarding.data.nextStep] : 0)
  }, [onboarding.data, step])

  const setStep = useCallback((s: number) => {
    setStepState(s)
    setFurthest((f) => Math.max(f, s))
  }, [])

  const goTo = useCallback((s: number) => setStepState(s), [])

  const value = useMemo<SetupValue>(() => ({
    step, setStep, goTo, reachable, files, setFiles, reading, setReading,
  }), [step, setStep, goTo, reachable, files, reading])

  return <SetupContext.Provider value={value}>{children}</SetupContext.Provider>
}
