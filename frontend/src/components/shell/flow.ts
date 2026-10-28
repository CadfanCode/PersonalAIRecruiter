import { useApplications } from '../../api/applications'
import { useApplyStatus } from '../../api/apply'

export type StageKey = 'mode' | 'run' | 'documents' | 'apply' | 'submit'

export interface Stage {
  key: StageKey
  label: string
  to: string
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

/** Which stage a path belongs to; null for pages outside the flow (CV database, Settings and so on). */
export function stageOf(pathname: string): StageKey | null {
  if (pathname === '/' || pathname === '/runs') return 'mode'
  if (pathname.startsWith('/runs/')) return 'run'
  if (pathname.startsWith('/documents')) return 'documents'
  if (pathname.startsWith('/apply')) return 'apply'
  if (pathname.startsWith('/submit') && !pathname.startsWith('/submitted')) return 'submit'
  return null
}

export type StageState = 'current' | 'done' | 'available' | 'locked'

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
  const stages: Stage[] = [
    { key: 'mode', label: 'Mode', to: '/' },
    { key: 'run', label: 'Search and ranking', to: run ? `/runs/${run}` : '/' },
    { key: 'documents', label: 'Documents', to: '/documents' },
    { key: 'apply', label: 'Apply', to: '/apply' },
    { key: 'submit', label: 'Submit', to: '/submit' },
  ]
  const has: Record<StageKey, boolean> = {
    mode: true,
    run: run !== null,
    documents: open.length > 0,
    apply: open.some((a) => a.cv && a.letter) || tabs.length > 0,
    submit: tabs.some((t) => t.phase === 'READY_FOR_SUBMISSION' || t.status === 'APPLY_MANUALLY' || t.status === 'EMAIL_ONLY') || open.some((a) => a.readyToApply),
  }
  const order = stages.map((s) => s.key)
  const states = new Map<StageKey, StageState>()
  for (const s of stages) {
    if (s.key === current) states.set(s.key, 'current')
    else if (!has[s.key]) states.set(s.key, 'locked')
    else if (current && order.indexOf(s.key) < order.indexOf(current)) states.set(s.key, 'done')
    else states.set(s.key, 'available')
  }
  return { stages, state: (k: StageKey) => states.get(k) ?? 'locked', current }
}
