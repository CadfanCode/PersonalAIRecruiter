import { useEffect, useRef } from 'react'
import { useApplications } from '../../api/applications'
import { useApplyStatus } from '../../api/apply'
import { useToast } from '../Toasts'

/** Asks for permission to show system notifications. Call it from a click: browsers only allow the prompt then. */
export function enableSystemNotifications() {
  if (typeof Notification === 'undefined' || Notification.permission !== 'default') return
  void Notification.requestPermission()
}

/**
 * Tells the user when an application has become ready for them to submit: a message in the app, and a system notification
 * when they allowed it. It runs in the shell, so it works on every page.
 */
export function useReadyNotifications() {
  const status = useApplyStatus().data
  const apps = useApplications().data
  const toast = useToast()
  const seen = useRef<Map<number, string> | null>(null)

  useEffect(() => {
    if (!status) return
    const now = new Map(status.map((t) => [t.jobId, t.phase]))
    const before = seen.current
    seen.current = now
    if (before === null) return // the first look only records what is there; nothing "became" ready
    for (const t of status) {
      if (t.phase === 'READY_FOR_SUBMISSION' && before.get(t.jobId) !== 'READY_FOR_SUBMISSION') {
        const title = apps?.find((a) => a.jobId === t.jobId)?.title ?? t.title
        const text = `${title} is ready for you to submit`
        toast(text)
        if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
          try {
            new Notification('JobPilot', { body: text, icon: '/jobpilot.svg' })
          } catch {
            // some environments refuse to construct notifications; the in-app message was shown
          }
        }
      }
    }
  }, [status, apps, toast])
}
