import type { Application } from '../../api/applications'
import type { TabView } from '../../api/apply'
import { Pill, type Tone } from '../ui/Pill'

/** What an application is doing, in the words the user sees. SUBMITTED comes from the user's own confirmation. */
export type Phase = 'SUBMITTED' | 'BY_HAND' | 'QUEUED' | 'FILLING' | 'NEEDS_LOGIN' | 'NEEDS_ATTENTION' | 'READY_FOR_SUBMISSION' | 'FAILED' | 'CLOSED' | 'NOT_STARTED'

export const PHASE_LABEL: Record<Phase, [string, Tone]> = {
  SUBMITTED: ['Submitted', 'success'],
  QUEUED: ['Waiting for a free tab', 'neutral'],
  FILLING: ['Filling in', 'accent'],
  NEEDS_LOGIN: ['Needs login', 'warn'],
  NEEDS_ATTENTION: ['Needs attention', 'warn'],
  BY_HAND: ['Apply by hand', 'accent'],
  READY_FOR_SUBMISSION: ['Ready for submission', 'success'],
  FAILED: ['Failed', 'danger'],
  CLOSED: ['Tab closed', 'neutral'],
  NOT_STARTED: ['Not started', 'neutral'],
}

export function phaseOf(app: Application | undefined, tab: TabView | undefined): Phase {
  if (app?.submittedAt) return 'SUBMITTED'
  if (tab && ['APPLY_MANUALLY', 'EMAIL_ONLY', 'NO_APPLY_LINK'].includes(tab.status)) return 'BY_HAND'
  return tab ? tab.phase : 'NOT_STARTED'
}

export function PhasePill({ phase }: { phase: Phase }) {
  const [label, tone] = PHASE_LABEL[phase]
  return <Pill tone={tone}>{label}</Pill>
}

/** Applications the user finishes by hand: a filled form to submit, or an ad that cannot be opened by JobPilot at all. */
export const isSubmitStage = (tab: TabView | undefined) =>
  !!tab && (tab.phase === 'READY_FOR_SUBMISSION' || tab.status === 'APPLY_MANUALLY' || tab.status === 'EMAIL_ONLY' || tab.status === 'NO_APPLY_LINK')
