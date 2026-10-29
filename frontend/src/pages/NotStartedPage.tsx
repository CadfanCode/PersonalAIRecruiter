import { Navigate, useParams } from 'react-router-dom'
import { NotStarted } from '../components/NotStarted'
import { STAGE_META, type StageKey, stageBefore, useFlow } from '../components/shell/flow'

/** Where a stage that cannot be opened yet leads: what it is, and what to complete first. */
export function NotStartedPage() {
  const { stage } = useParams()
  const { stages } = useFlow('/')
  const meta = stage ? STAGE_META[stage as StageKey] : undefined
  if (!meta) return <Navigate to="/" replace />
  // It may have become available since the link was followed (or the page was reloaded): then go straight there.
  const now = stages.find((st) => st.key === meta.key)
  if (now && !now.to.startsWith('/not-started/')) return <Navigate to={now.to} replace />
  let before = stageBefore(meta.key)
  if (!before) return <Navigate to={meta.to} replace />
  // Name the earliest stage that still has to be done, not one that is itself waiting for another.
  const locked = (key: StageKey) => stages.find((st) => st.key === key)?.to.startsWith('/not-started/')
  for (let prev = before; locked(prev.key) && stageBefore(prev.key); prev = stageBefore(prev.key)!) before = stageBefore(prev.key)!
  return <NotStarted stage={meta.label} first={before.label} to={stages.find((st) => st.key === before.key)?.to ?? before.to} />
}
