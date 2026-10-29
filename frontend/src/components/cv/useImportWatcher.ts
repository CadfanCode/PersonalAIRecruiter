import { useEffect, useRef } from 'react'
import { useImportJob } from '../../api/cv'
import { useToast } from '../Toasts'

/**
 * Tells the user when a CV they uploaded has been read (or could not be), on whatever page they are. The reading itself runs on
 * the server, so it never depends on the page that started it.
 */
export function useImportWatcher() {
  const job = useImportJob().data
  const toast = useToast()
  const seen = useRef<string | null>(null)
  const status = job ? `${job.runId}:${job.status}` : null

  useEffect(() => {
    const before = seen.current
    seen.current = status
    if (!job || before === null || before === status || !before.endsWith(':RUNNING') || !before.startsWith(`${job.runId}:`)) return
    if (job.status === 'DONE') toast('Your CV has been read. Review it in the CV database.')
    if (job.status === 'FAILED') toast(`Reading your CV failed: ${job.message}`, 'error')
  }, [status, job, toast])
}
