import { useNavigate } from 'react-router-dom'
import { useReopenOnboarding } from '../../api/onboarding'
import { Button } from '../Form'
import { Panel } from '../Panel'

export function SetupPanel() {
  const reopen = useReopenOnboarding()
  const navigate = useNavigate()
  return (
    <Panel title="Setup">
      <p className="text-sm text-[var(--muted)]">Go through the welcome steps again: add another CV, change how the AI is connected, the country or the search mode. Nothing you have is deleted.</p>
      <div className="mt-4"><Button variant="ghost" disabled={reopen.isPending} onClick={() => reopen.mutate(undefined, { onSuccess: () => navigate('/welcome') })}>Run setup again</Button></div>
      {reopen.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{reopen.error.message}</p>}
    </Panel>
  )
}
