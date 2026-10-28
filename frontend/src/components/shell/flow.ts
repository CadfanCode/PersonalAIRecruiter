import { useApplications } from '../../api/applications'
import { useApplyStatus } from '../../api/apply'

export type StageKey = 'mode' | 'run' | 'documents' | 'apply' | 'submit'

export interface Stage {
  key: StageKey
  label: string
  to: string
}

const STAGE_ORDER: StageKey[] = ['mode', 'run', 'documents', 'apply', 'submit']
export const STAGE_META: Record<StageKey, { key: StageKey; label: string; to: string }> = {
  mode: { key: 'mode', label: 'Mode', to: '/' },
  run: { key: 'run', label: 'Search and ranking', to: '/runs' },
  documents: { key: 'documents', label: 'Documents', to: '/documents' },
  apply: { key: 'apply', label: 'Apply', to: '/apply' },
  submit: { key: 'submit', label: 'Submit', to: '/submit' },
}

const LAST_RUN = 'jobpilot.lastRun'

export function rememberRun(id: number) {
  try {
    localStorage.setItem(LAST_RUN, String(id))
  } catch {
    // the flow bar just has no "back to my run" link then
  }
}

function lastRun(): number | null {
  try {
    const raw = localStorage.getItem(LAST_RUN)
    return raw ? Number(raw) : null
  } catch {
    return null
  }
}

/** Which stage a path belongs to (a stage's "not started yet" page belongs to it too); null for pages outside the flow (CV database, Settings and so on). */
export function stageOf(pathname: string): StageKey | null {
  const waiting = /^\/not-started\/(mode|run|documents|apply|submit)$/.exec(pathname)
  if (waiting) return waiting[1] as StageKey
  if (pathname === '/' || pathname === '/runs') return 'mode'
  if (pathname.startsWith('/runs/')) return 'run'
  if (pathname.startsWith('/documents')) return 'documents'
  if (pathname.startsWith('/apply')) return 'apply'
  if (pathname.startsWith('/submit') && !pathname.startsWith('/submitted')) return 'submit'
  return null
}

export type StageState = 'current' | 'done' | 'available' | 'locked'

/** The stage whose work has to be done before this one can start. */
export const stageBefore = (key: StageKey): Stage | undefined => {
  const order = STAGE_ORDER
  const i = order.indexOf(key)
  return i > 0 ? STAGE_META[order[i - 1]] : undefined
}

/**
 * The five stages with where each one leads and whether it can be opened now. The current stage is bold, stages before it that
 * have something to show are "done" and open, later ones open only when there is something to show; the rest are faded.
 */
export function useFlow(pathname: string) {
  const apps = useApplications().data ?? []
  const tabs = useApplyStatus().data ?? []
  const current = stageOf(pathname)
  const run = lastRun()
  const open = apps.filter((a) => !a.submittedAt)
  const stages: Stage[] = STAGE_ORDER.map((k) => (k === 'run' ? { ...STAGE_META.run, to: run ? `/runs/${run}` : '/runs' } : STAGE_META[k]))
  const has: Record<StageKey, boolean> = {
    mode: true,
    run: run !== null,
    documents: open.length > 0,
    apply: open.some((a) => a.cv && a.letter) || tabs.length > 0,
    submit: tabs.some((t) => t.phase === 'READY_FOR_SUBMISSION' || t.status === 'APPLY_MANUALLY' || t.status === 'EMAIL_ONLY') || open.some((a) => a.readyToApply),
  }
  const order = STAGE_ORDER
  const states = new Map<StageKey, StageState>()
  for (const s of stages) {
    if (s.key === current) states.set(s.key, 'current')
    else if (!has[s.key]) states.set(s.key, 'locked')
    else if (current && order.indexOf(s.key) < order.indexOf(current)) states.set(s.key, 'done')
    else states.set(s.key, 'available')
  }
  // A stage that cannot be opened yet still has a destination: a page that says what to complete first.
  const reachable = stages.map((st) => (has[st.key] ? st : { ...st, to: `/not-started/${st.key}` }))
  return { stages: reachable, state: (k: StageKey) => states.get(k) ?? 'locked', current }
}
